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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
    fun fetchGroups(): List<String> {
        val cached = prefs.getString("groups", null)
        val fresh = prefs.getLong("groups_ts", 0L)
        val ttl = 7L * 24 * 60 * 60 * 1000
        if (cached != null && System.currentTimeMillis() - fresh < ttl) {
            return cached.split("|||").filter { it.isNotBlank() }
        }
        val raw = post(ApiPaths.GROUPS, null)
        val list = GsonHolder.gson.fromJson(raw, Array<String>::class.java).toList()
        prefs.edit()
            .putString("groups", list.joinToString("|||"))
            .putLong("groups_ts", System.currentTimeMillis())
            .apply()
        return list
    }

    /** Расписание группы */
    fun fetchSchedule(group: String): String {
        val key = "sched_${group}"
        val raw = post(ApiPaths.DATA, FormBody.Builder().add("group", group).build())
        prefs.edit().putString(key, raw).putLong("${key}_ts", System.currentTimeMillis()).apply()
        return raw
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
            prefs.edit().putString(key, raw).putLong("${key}_ts", System.currentTimeMillis()).apply()
        } else {
            prefs.edit().remove(key).remove("${key}_ts").apply()
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
    fun lastUpdatedAt(role: Role, entity: String): Long {
        val candidates = when (role) {
            Role.STUDENT -> listOf(prefs.getLong("sched_${entity}_ts", 0L))
            Role.AUDIENCE -> listOfNotNull(
                entity.trim().toIntOrNull()?.let { prefs.getLong("aud_${it}_ts", 0L) },
                prefs.getLong("sched_${entity}_ts", 0L),
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
            // назад» сразу после нажатия — тоже враньё, только в другую сторону.
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
            return GsonHolder.gson.fromJson(cached, Array<Audience>::class.java).toList()
        }
        val raw = post(ApiPaths.AUDIENCES, null)
        val list = GsonHolder.gson.fromJson(raw, Array<Audience>::class.java).toList()
        prefs.edit()
            .putString(KEY_AUD, raw)
            .putLong("${KEY_AUD}_ts", System.currentTimeMillis())
            .apply()
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
                val k = "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|${l.classInfo?.name}|${l.group?.name}"
                out[k] = l
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
