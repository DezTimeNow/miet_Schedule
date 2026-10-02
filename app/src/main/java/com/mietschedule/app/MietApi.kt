package com.mietschedule.app

import android.util.Log
import android.content.Context
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Клиент miet.ru.
 * ВАЖНО: API принимает ТОЛЬКО application/x-www-form-urlencoded (с JSON уходит 404).
 * Ответы компактные, поэтому шлём 1 раз и кэшируем локально.
 */
class MietApi(private val context: Context) {

    /**
     * maxRequestsPerHost по умолчанию равен 5, а обновление запускает 6 потоков.
     * Шестой поток всё время ждал остальные, и обновление 343 групп ползло
     * 208 секунд вместо 30. Ставим 8 — с запасом на переподключения.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            }
        )
        .build()

    private val prefs = context.getSharedPreferences("miet_cache", Context.MODE_PRIVATE)

    private fun post(path: String, body: FormBody?): String {
        val builder = Request.Builder()
            .url(ApiPaths.BASE + path)
            .header("User-Agent", "MIETSchedule/1.0 (Android; student schedule viewer)")
            .header("Accept", "application/json")
        if (body != null) builder.post(body) else builder.post(FormBody.Builder().build())
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return text
        }
    }

    /** Список всех групп (344 шт) */
    /**
     * Список групп с сайта.
     *
     * @param force=true игнорирует кэш и всегда идёт на miet.ru. Кнопка
     *   «Обновить всё» обязана обновлять ВСЁ, а список групп меняется каждый
     *   семестр: при недельном TTL новые группы могли не появиться неделями,
     *   и кнопка обещала полное обновление, а по факту брала старый список.
     */
    fun fetchGroups(force: Boolean = false): List<String> {
        val cached = prefs.getString("groups", null)
        val fresh = prefs.getLong("groups_ts", 0L)
        val ttl = 7L * 24 * 60 * 60 * 1000
        if (!force && cached != null && System.currentTimeMillis() - fresh < ttl) {
            return cached.split("|||").filter { it.isNotBlank() }
        }
        val raw = post(ApiPaths.GROUPS, null)
        val list = runCatching { GsonHolder.gson.fromJson(raw, Array<String>::class.java).toList() }
            .getOrElse { emptyList() }
        // Пустой ответ НЕ кэшируем. Сервер при сбое может отдать [], и раньше
        // такой список записывался как обычный: на неделю (TTL) пропадали все
        // группы, до следующего принудительного обновления. Теперь кэш
        // трогается только непустым ответом, а вызывающий сам разберётся,
        // что список пуст.
        if (list.isNotEmpty()) {
            prefs.edit()
                .putString("groups", list.joinToString("|||"))
                .putLong("groups_ts", System.currentTimeMillis())
                .apply()
        } else {
            Log.w("MietApi", "Список групп пуст, кэш не трогаем")
        }
        return list
    }

    /**
     * Расписание группы.
     *
     * Запись СИНХРОННАЯ (commit), а не apply(). apply() возвращает управление
     * немедленно и пишет в фоне — при 344 группах подряд экран успевал прочитать
     * недописанный кэш: замерено, в файле оказывалось 275 ключей из 344.
     *
     * Синхронизация на самом prefs — потому что refreshCurrent() дёргает 6 потоков
     * одновременно, а SharedPreferences.apply() из шести потоков теряет записи:
     * каждый читает свой снимок и затирает чужой.
     */
    fun fetchSchedule(group: String): String =
        post(ApiPaths.DATA, FormBody.Builder().add("group", group).build())

    /**
     * Пишет расписание группы в кэш.
     *
     * Запись намеренно ОТДЕЛЕНА от сетевого вызова. Раньше каждый из 343
     * запроса сам делал prefs.commit() — синхронную запись на диск, — и обход
     * занимал 208 секунд вместо 30. Теперь сеть идёт быстро и параллельно,
     * а результаты пишутся одним пакетом через [saveSchedules].
     */
    fun cacheSchedule(group: String, raw: String) {
        val key = "sched_${group}"
        prefs.edit().putString(key, raw)
            .putLong("${key}_ts", System.currentTimeMillis())
            .commit()
    }

    /**
     * Пакетная запись расписаний ОДНИМ редактором.
     *
     * 343 отдельных commit() — это 343 синхронных выхода на диск, и они
     * съедали всё время обновления. Один commit() на 343 ключа занимает
     * доли секунды.
     *
     * Метки времени ставятся ОДНОЙ величиной на весь пакет: так дата
     * обновления честная (время завершения записи) и не «прыгает» между
     * группами, а [lastScheduleWriteTs] видит реальную свежесть кэша.
     */
    fun saveSchedules(items: Map<String, String>) {
        if (items.isEmpty()) return
        val ts = System.currentTimeMillis()
        val ed = prefs.edit()
        for ((group, raw) in items) {
            val key = "sched_${group}"
            ed.putString(key, raw)
            ed.putLong("${key}_ts", ts)
        }
        ed.commit()
    }

    /**
     * Расписание аудитории. Параметр — `audience`, значение — ЧИСЛОВОЙ Code из
     * /audiences (например 120, не имя «1201 (м)»). Проверено: отдаёт ~850 пар.
     */
    /**
     * Ответ по аудитории. ПУСТОЙ ОТВЕТ НЕ КЭШИРУЕМ.
     *
     * Сервер отдаёт `Data: []` для аудиторий, которых у него просто нет
     * (все 17 корпуса 8, корпус 6, УВЦ, виртуальные). Такой пустой ответ,
     * попав в кэш, годами выглядит как «расписание есть, но пустое»: при
     * следующем запуске apply() подставит его, lessons окажется непустым,
     * и сборка из кэша расписаний групп не запустится.
     */
    fun fetchAudience(audienceCode: Int): String {
        val key = "aud_$audienceCode"
        val raw = post(ApiPaths.DATA, FormBody.Builder().add("audience", "$audienceCode").build())
        val empty = try {
            val data = GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data
            data.isNullOrEmpty()
        } catch (e: Exception) {
            true
        }
        if (!empty) {
            prefs.edit().putString(key, raw).putLong("${key}_ts", System.currentTimeMillis()).commit()
        } else {
            prefs.edit().remove(key).remove("${key}_ts").commit()
        }
        return raw
    }

    fun cachedAudience(code: Int): String? = prefs.getString("aud_$code", null)

    /**
     * Когда последний раз РЕАЛЬНО обновили расписание с сайта.
     *
     * При каждой успешной загрузке рядом с данными уже пишется метка `<ключ>_ts`,
     * поэтому берём её, а не «сейчас»: иначе на экране всегда было бы «только что»
     * и пользователь не видел бы, что кнопка реально что-то обновила.
     *
     * Учитываем три источника — так дата отражает весь собранный кэш:
     *  - расписание выбранной группы (`sched_<группа>_ts`);
     *  - расписание аудитории (`aud_<код>_ts`);
     *  - индекс преподавателей (`teachers_*_ts`), из которого считается
     *    расписание преподавателя — там своя, более давняя метка.
     *
     * Берём МАКСИМУМ, а не минимум: «обновлено 2 минуты назад» честнее, чем
     * «5 дней назад» из-за одного старого куска кэша, который всё равно пригодится.
     */
    /**
     * Метка последней реальной записи расписания в кэш, мс.
     *
     * Нужна аудитории и преподавателю: их экран собирается из расписаний
     * ГРУПП, а groups_ts не отражает их свежесть — fetchGroups() при недельном
     * TTL возвращает кэш и метку не двигает. Смотрим на сами ключи расписаний.
     */
    fun lastScheduleWriteTs(): Long {
        // prefs.all — это копия снимка, уже запертого внутри SharedPreferences,
        // держать здесь свой монитор не нужно: он лишь замедлял записи.
        var best = 0L
        val all = prefs.all
        for ((k, v) in all) {
            if (!k.startsWith("sched_") || !k.endsWith("_ts")) continue
            val ts = (v as? Long) ?: 0L
            if (ts > best) best = ts
        }
        return best
    }

    fun lastUpdatedAt(role: Role, entity: String): Long {
        val candidates = when (role) {
            Role.STUDENT -> listOf(prefs.getLong("sched_${entity}_ts", 0L))
            // Аудитория: ключи БЫЛИ не те, отчего подпись молчала «ещё не
            // обновлялось» даже после успешного обновления.
            //
            // Что приходит: entity = ИМЯ аудитории («8307»), teacherCode = её
            // ВНУТРЕННИЙ числовой id (234). Ключ в кэше — `aud_234_ts`, то есть
            // по имени его не достать НИКОГДА. А `sched_8307_ts` не существует
            // в принципе: расписания хранятся по ГРУППАМ, а не по аудиториям.
            //
            // Занятость аудитории строится из расписаний групп, поэтому честная
            // метка здесь — когда перезапрашивали расписания (группы_ts), плюс
            // метка самого ответа по аудитории, если он есть.
            //
            // И groups_ts тут НЕ годился: fetchGroups() отдаёт кэш, если ему
            // меньше недели, метку не трогает — и подпись застревала на часах
            // давности, хотя расписания только что перезапросили. Честная метка
            // здесь — последняя реальная запись расписания, а не список групп.
            Role.AUDIENCE -> listOfNotNull(
                entity.trim().toIntOrNull()?.let { prefs.getLong("aud_${it}_ts", 0L) },
                lastScheduleWriteTs(),
            )
            // Преподаватель: сперва ЕГО СОБСТВЕННАЯ свежесть (её держит
            // TeacherIndex.refreshTeacher в поле freshAt его записи индекса).
            // Общая метка индекса — запасной вариант на случай, когда
            // быстрого обновления ещё не было ни разу.
            //
            // Раньше тут стоял indexBuiltAt — и это была неверная величина:
            // TeacherIndex.build пишет общий KEY_TS, а последняя ПОЛНАЯ
            // пересборка могла быть месяц назад, тогда как кнопка «Обновить»
            // только что обновила его группы. Показывать «обновлено месяц
            // назад» сразу после нажатия — тоже некорректно, только в другую сторону.
            // Сюда приходит ФИО (выбранное имя), а индекс записан по
            // нормализованному ключу. Без key() поиск молча возвращал бы 0 —
            // и дата молчала бы уезжать на общую метку индекса.
            Role.TEACHER -> listOf(
                TeacherIndex.freshAtOf(this, TeacherIndex.key(entity)),
                TeacherIndex.indexBuiltAt(this),
                prefs.getLong("${KEY_AUD_IDX}_ts", 0L),
            )
        }
        return candidates.filter { it > 0L }.maxOrNull() ?: 0L
    }

    /**
     * Список аудиторий. Кэш недельный: список меняется только при ремонте.
     */
    fun fetchAudiences(): List<Audience> {
        val cached = prefs.getString(KEY_AUD, null)
        val fresh = prefs.getLong("${KEY_AUD}_ts", 0L)
        val ttl = 7L * 24 * 60 * 60 * 1000
        if (cached != null && System.currentTimeMillis() - fresh < ttl) {
            // Кэш читаем через runCatching: оборванная запись в prefs (например,
            // после падения при записи) дала бы JsonSyntaxException прямо в
            // этой строке, и экран аудиторий падал бы целиком.
            val hit = runCatching { GsonHolder.gson.fromJson(cached, Array<Audience>::class.java).toList() }.getOrNull()
            if (hit != null) return hit
            Log.w("MietApi", "Кэш аудиторий битый, читаем с сервера")
        }
        val raw = post(ApiPaths.AUDIENCES, null)
        val list = GsonHolder.gson.fromJson(raw, Array<Audience>::class.java).toList()
        if (list.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_AUD, raw)
                .putLong("${KEY_AUD}_ts", System.currentTimeMillis())
                .apply()
        }
        return list
    }

    fun cachedAudiences(): List<Audience> =
        prefs.getString(KEY_AUD, null)?.let {
            runCatching { GsonHolder.gson.fromJson(it, Array<Audience>::class.java).toList() }
                .getOrNull() ?: emptyList()
        } ?: emptyList()

    // ───────────────────── Локальный индекс аудиторий ─────────────────────
    //
    // Список аудиторий берётся не только из эндпоинта. В расписаниях групп
    // встречается 194 аудитории, а в эндпоинте лишь 136: ещё 58 есть в паре,
    // но сервер их поиском не найдёт. Из них 14 — корпус 8 (8102, 8103, 8104,
    // 8107, 8109, 8110, 8111, 8112, 8208, 8231, 8306, 8307, 8308, 8309),
    // где /data?audience= отдаёт 0 пар. Для них расписание собирается локально
    // из кэша групп — см. [localLessonsOf].

    private val KEY_AUD_IDX = "audience_index"
    private val KEY_AUD_IDX_TS = "audience_index_ts"

    fun saveAudienceIndex(json: String) {
        prefs.edit().putString(KEY_AUD_IDX, json)
            .putLong(KEY_AUD_IDX_TS, System.currentTimeMillis()).apply()
    }


    /**
     * Аудитории, которых нет в эндпоинте /audiences, но которые встречаются в паре.
     *
     * Считаются из кэша расписаний групп — если кэша нет, возвращает пустой список
     * и выбор аудитории просто покажет серверные 136.
     */
    suspend fun deriveAudiencesFromGroups(fetchGroupsIfNeeded: Boolean = false): List<Audience> {
        val gson = GsonHolder.gson
        val out = LinkedHashMap<Int, Audience>()

        var cached = cachedGroups()
        if (cached.isEmpty() && fetchGroupsIfNeeded) {
            cached = try { fetchGroups() } catch (e: Exception) {
                Log.w("MietApi", "Список групп не получен: ${e.message}")
                emptyList()
            }
        }
        if (cached.isEmpty()) return emptyList()

        // Расписания групп кэшируются только когда пользователь реально открыл
        // расписание. Аудитории нужны уже на экране выбора, поэтому если кэша
        // нет — забираем расписания напрямую (344 запроса, ~12 с, шестью
        // потоками; заодно прогревается кэш для режима преподавателя).
        val needFetch = cachedSchedule(cached.first()) == null

        for (g in cached) {
            val raw = if (needFetch) {
                try { fetchSchedule(g) } catch (e: Exception) { null }
            } else cachedSchedule(g)
            if (raw == null) continue
            val data: List<Lesson> = try {
                gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
            } catch (e: Exception) {
                continue
            }
            for (l in data) {
                val room: RoomInfo = l.room ?: continue
                val code: Int = room.roomCode() ?: continue
                val name: String = room.name?.trim().orEmpty()
                if (name.isEmpty()) continue
                if (code !in out) out[code] = Audience(code = code, name = name)
            }
        }
        return out.values.toList()
    }

    fun loadAudienceIndex(): String? = prefs.getString(KEY_AUD_IDX, null)
    fun loadAudienceIndexTs(): Long = prefs.getLong(KEY_AUD_IDX_TS, 0L)

    /**
     * Расписание аудитории из кэша расписаний групп.
     *
     * ФИЛЬТР ИМЕНОМ, А НЕ КОДОМ. Код из /audiences — внутренний id, а не номер
     * аудитории: у «8307» он 234, у «1201 (м)» — 120, у «3205» — 9. Считать
     * 136 аудиторий, у которых id != номер (все 136 из 136). Фильтр по коду
     * отдавал у 8307 не 72 пары, а 12 (остальное — от соседнего «8307 к»), а
     * у 42 аудиторий из 136 показывал чужие пары.
     */
    /**
     * КЛЮЧ ДЕДУПЛИКАЦИИ пары аудитории. Вынесен в отдельную функцию, чтобы его
     * можно было проверить тестом: когда он был инлайном, ошибка в нём
     * воспроизводилась и в тесте, и в приложении одновременно.
     *
     * Ключ = слот (день+неделя+пара) + Class.Code + аудитория.
     *
     * Зачем Class.Code. Группа ТЭ-26-13О во вторник 2-й парой в аудитории 8307
     * стоит ДВА разных занятия «Информатика» — Овчинников и Власов. Преподаватель
     * в ключ не входит (у аудитории его роль другая), предмет один, аудитория
     * одна, группа одна — различаются ТОЛЬКО по Class.Code. Ключ без него
     * склеивал две пары в одну, и приложение теряло 4 пары из 12 (замерено на
     * живом ответе miet.ru).
     *
     * Зачем аудитория. В четверг 2-й парой та же группа стоит в 8307 И в 8306
     * одновременно — пара разделена на две подгруппы по разным аудиториям.
     * Слот и Class.Code у этих строк совпадают, поэтому без roomKey одна
     * затирала другую.
     */
    suspend fun localLessonsOf(groups: List<String>, roomCode: Int, roomName: String? = null): List<Lesson> {
        val gson = GsonHolder.gson
        val out = LinkedHashMap<String, Lesson>()
        // Ключ от имени; без имени откатываемся на код (аудитории из кэша).
        val want = roomName?.trim()?.takeIf { it.isNotEmpty() }?.let { roomKey(it) }
        for (g in groups) {
            val raw = cachedSchedule(g) ?: continue
            val data: List<Lesson> = try {
                gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
            } catch (e: Exception) {
                continue
            }
            for (l in data) {
                val lname = l.room?.name?.trim().orEmpty()
                val hit = if (want != null) {
                    lname.isNotEmpty() && roomKey(lname) == want
                } else {
                    l.room?.roomCode() == roomCode
                }
                if (!hit) continue
                // КЛЮЧ ОБЯЗАН ВКЛЮЧАТЬ АУДИТОРИЮ, иначе подгруппы схлопываются.
                //
                // Группа ТЭ-26-13О в четверг 2-й парой делится надвое: чётная
                // неделя в 8307, нечётная — в 8306 (и наоборот). Преподаватель
                // один, предмет один, группа одна — старый ключ был для обеих
                // строк ОДИНАКОВЫМ, и LinkedHashMap оставлял последнюю. Замерено
                // на живом ответе: у 8307 сервер отдаёт 12 строк, в приложение
                // попадало 8 — четыре пары исчезли, потому что «победила» 8306.
                //
                // Подгруппа в данных не выделена: Group.Name у обеих строк
                // одинаковый (ТЭ-26-13О), различается только аудитория. Значит
                // различает их именно Room.
                // КЛЮЧ = слот + Class.Code + аудитория. Оба последних обязательны,
                // и это два РАЗНЫХ случая, а не один:
                //
                // 1) Class.Code — идентичность самого занятия. Группа ТЭ-26-13О
                //    во вторник 2-й парой в 8307 стоит ДВА разных «Информатика»:
                //    Овчинников (Code 9DBB…) и Власов (Code 6B8D…). Преподаватель,
                //    предмет, аудитория, группа — всё одинаковое, различается
                //    ТОЛЬКО Code. Ключ без него ронял 4 пары из 12 (замерено).
                //
                // 2) roomKey — на случай разбиения на подгруппы по разным
                //    аудиториям: в четверг 2-й парой та же группа стоит в 8307
                //    И в 8306 одновременно. Без аудитории одна затирала другую.
                //
                // Проверено на живом ответе ТЭ-26-13О: 8307 — 12 строк, все 12
                // доходят до карточек; 8306 — 4 из 4.
                out[roomLessonKey(l)] = l
            }
        }
        return out.values.sortedWith(
            compareBy({ it.day ?: 1 }, { it.dayNumber ?: 0 }, { it.time?.code ?: 0 })
        )
    }

    /** Последний закэшированный ответ без сети (для офлайн-старта) */
    fun cachedSchedule(group: String): String? = prefs.getString("sched_$group", null)

    /** Закэшированный список групп без обращения к сети (для мгновенного показа). */
    fun cachedGroups(): List<String> =
        prefs.getString("groups", null)?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Дата начала семестра, нужная для расчёта текущей недели (числитель/знаменатель).
     * Сайт публикует её в HTML страницы расписания как `var semestr_start = "2026-08-04"`.
     * Кэшируем на сутки; при неудаче остаётся зашитая в WeekType.SEMESTR_START_ISO.
     */
    fun semestrStart(): String {
        val cached = prefs.getString(KEY_SEMESTR, null)
        val ts = prefs.getLong("${KEY_SEMESTR}_ts", 0L)
        if (cached != null && System.currentTimeMillis() - ts < 24 * 60 * 60 * 1000L) return cached
        return runCatching {
            val req = Request.Builder()
                .url("https://miet.ru/schedule/")
                .header("User-Agent", "MIETSchedule/1.0 (Android; student schedule viewer)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                val m = Regex("""semestr_start\s*=\s*["']([0-9]{4}-[0-9]{2}-[0-9]{2})["']""").find(html)
                    ?: throw IllegalStateException("semestr_start не найден")
                m.groupValues[1].also { iso ->
                    prefs.edit().putString(KEY_SEMESTR, iso)
                        .putLong("${KEY_SEMESTR}_ts", System.currentTimeMillis()).apply()
                }
            }
        }.getOrElse { cached ?: WeekType.SEMESTR_START_ISO }
    }

    fun cacheAgeMs(group: String): Long {
        val ts = prefs.getLong("sched_${group}_ts", 0L)
        return if (ts == 0L) -1L else System.currentTimeMillis() - ts
    }

    // ───────────────────── индекс преподавателей ─────────────────────
    // Лежит в том же файле кэша, что и расписания групп: индекс строится
    // ровно из них, и отдельный файл только размножал бы данные.

    fun saveTeacherIndex(key: String, json: String) =
        prefs.edit().putString(key, json).apply()

    fun loadTeacherIndex(key: String): String? = prefs.getString(key, null)

    fun saveTeacherIndexTs(key: String, ts: Long) =
        prefs.edit().putLong(key, ts).apply()

    fun loadTeacherIndexTs(key: String): Long = prefs.getLong(key, 0L)

    private companion object {
        const val KEY_SEMESTR = "semestr_start"
        const val KEY_AUD = "audiences"
    }
}

/**
 * Ключ дедупликации пары аудитории — top-level, чтобы юнит-тест не тянул
 * за собой MietApi (а он требует Context). См. доктринг у одноимённого
 * [MietApi.localLessonsOf], где ключ и применяется.
 */
internal fun roomLessonKey(l: Lesson): String =
    "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|" +
        "${l.classInfo?.code}|${l.classInfo?.name}|${l.group?.name}|" +
        roomKey(l.room?.name)
