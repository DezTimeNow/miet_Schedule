package com.mietschedule.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Список преподавателей и их расписания.
 *
 * ВАЖНО: miet.ru расписание преподавателя НЕ ОТДАЁТ. В бандле сайта
 * (/schedule/assets/index-Bdwhd4-Q.js) всего три эндпоинта — groups, audiences,
 * data — и ни одного teacher; попытки передать teacher=/teacherId=/ФИО дают
 * пустой ответ или HTML-заглушку. Единственный источник — расписания групп.
 *
 * ИДЕНТИФИКАЦИЯ ПРЕПОДАВАТЕЛЯ. Нельзя брать Class.Code: это GUID предмета
 * («402a26bd-3b16-11f1-a7c9-0050569f2356»), и один преподаватель, ведущий
 * три предмета, попадает в список трижды — получалось 3974 «преподавателя»
 * вместо 681. Ключ — нормализованное Class.TeacherFull.
 *
 * Экономия памяти: индекс хранит только ФИО/код/число пар. Сами пары не
 * копируются — они уже лежат в кэше расписаний групп (те самые 344 записи
 * `sched_<группа>`), а [lessonsOf] просто перебирает этот кэш и оставляет строки
 * нужного преподавателя. Копия на 658 преподавателей весила бы десятки мегабайт.
 */
object TeacherIndex {

    /** Преподаватель: ФИО, короткая forma, код-ключ, сколько у него пар. */
    data class Teacher(
        val name: String,
        val short: String,
        val code: String = "",
        val pairCount: Int = 0,
        /**
         * Когда ЭТИМУ преподавателю в последний раз перезапрашивали с сайта
         * расписания его групп (мс). Не путать с общей меткой индекса
         * [KEY_TS]: та одна на всех 658 человек и двигается только полной
         * пересборкой. Без отдельного поля пришлось бы врать — показать
         * «только что» остальным преподавателям, чьи группы не трогали.
         */
        val freshAt: Long = 0L
    )

    private const val KEY_LIST = "teacher_index"
    private const val KEY_TS = "teacher_index_ts"
    private const val TAG = "TeacherIndex"

    /** Индекс живёт неделю: расписание меняется, но не каждый день. */
    private const val TTL = 7L * 24 * 60 * 60 * 1000

    /**
     * Ключ преподавателя — нормализованное ФИО. Пробелы вокруг и множественные
     * пробелы внутри встречаются в ответах сервера и должны давать одного и того
     * же человека, а не двух.
     */
    fun key(fullName: String): String =
        fullName.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Служебные записи вроде «Преподаватель УВЦ» — не человек, а канал, через
     * который одни и те же пары числятся за десятки групп: у них тысячи пар,
     * и в общем списке они только мешают. Но по прямой ссылке их открыть можно.
     */
    fun isService(name: String): Boolean =
        name.contains("УВЦ", ignoreCase = true) ||
            (name.startsWith("Преподаватель", ignoreCase = true) &&
                name.contains("практической", ignoreCase = true))

    /**
     * Собирает индекс преподавателей, перебирая расписания всех групп.
     * Расписания при этом кэшируются (их и так использует режим студента), так что
     * повторный запуск бесплатный — [lessonsOf] достанет пары из кэша.
     */
    suspend fun build(
        api: MietApi,
        groups: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Teacher> = withContext(Dispatchers.IO) {
        val names = ConcurrentHashMap<String, String>()   // key -> ФИО
        val shorts = ConcurrentHashMap<String, String>()  // key -> ФИО краткое
        val slots = ConcurrentHashMap<String, MutableSet<String>>() // key -> слоты

        // 344 группы последовательно — это минуты. Шесть параллельных потоков
        // дают те же ~12 секунд, но сервер не должен от этого захлебнуться,
        // поэтому потоков мало и между ними ничего не спит.
        val progress = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = groups.map { g ->
                pool.submit {
                    var raw: String? = null
                    try { raw = api.fetchSchedule(g) } catch (e: Exception) {
                        Log.w(TAG, "Не получили $g: ${e.message}")
                    }
                    val data: List<Lesson> = if (raw != null) {
                        try { GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList() }
                        catch (e: Exception) { emptyList() }
                    } else emptyList()

                    for (l in data) {
                        val c = l.classInfo ?: continue
                        val tName = c.teacherFull?.trim().orEmpty()
                        if (tName.isEmpty()) continue
                        val k = key(tName)
                        names[k] = tName
                        shorts.merge(k, c.teacher?.trim().orEmpty()) { a, b ->
                            if (a.length >= b.length) a else b
                        }
                        // Слот, а не строка: одна пара у преподавателя с 5 группами
                        // числится в расписании каждой из них, но это одна пара.
                        val slot = "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}"
                        slots.computeIfAbsent(k) { ConcurrentHashMap.newKeySet() }.add(slot)
                    }
                    onProgress(progress.incrementAndGet(), groups.size)
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val result = slots.keys.map { k ->
            Teacher(
                name = names[k] ?: k,
                short = shorts[k] ?: "",
                code = k,
                pairCount = slots[k]?.size ?: 0
            )
        }.sortedBy { it.name }

        api.saveTeacherIndex(KEY_LIST, GsonHolder.gson.toJson(result))
        api.saveTeacherIndexTs(KEY_TS, System.currentTimeMillis())
        Log.i(TAG, "Индекс преподавателей: ${result.size}")
        result
    }

    /**
     * Ключ метки времени индекса. Сделан публичным, потому что экран «О программе»
     * показывает дату последнего обновления и не должен знать про `private const`.
     */
    const val TS_KEY = KEY_TS

    /** Когда индекс преподавателей последний раз собирали с сайта, мс. */
    fun indexBuiltAt(api: MietApi): Long = api.loadTeacherIndexTs(TS_KEY)

    /** Кэшированный индекс или null, если его нет либо он протух. */
    fun cached(api: MietApi): List<Teacher>? {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return null
        val ts = api.loadTeacherIndexTs(KEY_TS)
        if (ts <= 0L || System.currentTimeMillis() - ts > TTL) return null
        return try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java).toList().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Не разобрали индекс: ${e.message}")
            null
        }
    }

    /**
     * БЫСТРОЕ обновление одного преподавателя: ходит в сеть только по его группам.
     *
     * Зачем это отдельной функцией, а не [build]: [build] перезапрашивает все
     * 344 группы ради построения индекса — это минуты. Преподавателю в среднем
     * 1–8 групп, то есть 10–30 секунд вместо минут, и кнопка «Обновить» становится
     * пригодной для нажатия, а не для того, чтобы уйти на кофе.
     *
     * Если групп преподавателя в кэше нет (первый запуск), индекс не на что
     * искать — тогда отдаём false, и вызывающий код падает в полную пересборку.
     */
    suspend fun refreshTeacher(
        api: MietApi,
        groups: List<String>,
        teacherCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        val mine = groupsOf(api, groups, teacherCode)
        if (mine.isEmpty()) return@withContext false

        // Шесть потоков по его собственным группам: их мало, сервер не заметит.
        val pool = Executors.newFixedThreadPool(min(6, mine.size))
        var ok = 0
        try {
            pool.invokeAll(mine.map { g ->
                Callable {
                    runCatching { api.fetchSchedule(g) }
                        .onSuccess { ok++ }
                        .onFailure { Log.w(TAG, "Не обновили $g: ${it.message}") }
                }
            })
        } finally {
            pool.shutdown()
        }
        if (ok == 0) return@withContext false

        // Метка свежести — только ему, в его запись. Общая KEY_TS не трогаем:
        // она принадлежит полной пересборке [build] и всем 658 преподавателям.
        val fresh = System.currentTimeMillis()
        val lessons = lessonsOf(api, groups, teacherCode)
        markFresh(api, teacherCode, fresh, lessons.size)
        Log.i(TAG, "Быстрое обновление: ${mine.size} групп, $ok скачано, ${lessons.size} пар")
        true
    }

    /**
     * Группы, в которых ведёт этот преподаватель — читаются из кэша, без сети.
     *
     * Нужны для быстрого обновления: перезапрашивать все 344 группы ради одного
     * человека — это минуты, а у препода обычно 1–8 групп.
     */
    suspend fun groupsOf(api: MietApi, groups: List<String>, teacherCode: String): List<String> =
        withContext(Dispatchers.IO) {
            val gson = GsonHolder.gson
            val out = LinkedHashSet<String>()
            for (g in groups) {
                val raw = api.cachedSchedule(g) ?: continue
                val data: List<Lesson> = try {
                    gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
                } catch (e: Exception) {
                    continue
                }
                if (data.any { key(it.classInfo?.teacherFull.orEmpty()) == teacherCode }) out.add(g)
            }
            out.toList()
        }

    /**
     * Обновляет метку свежести ОДНОГО преподавателя, не пересобирая индекс.
     *
     * Пересборка [build] ходит в сеть по всем 344 группам — это минуты. Здесь
     * же мы уже перезапросили его группы, поэтому честнее дописать свежесть
     * прямо в его запись. Общая метка [KEY_TS] при этом НЕ трогается: она
     * принадлежит полной пересборке и всем остальным преподавателям сразу.
     */
    fun markFresh(api: MietApi, teacherCode: String, ts: Long, pairCount: Int) {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return
        // Именно toMutableList(): toList() даёт неизменяемый список, и запись
        // list[i] = … не компилируется («No 'set' operator method providing
        // array access») — ошибка называет индекс, а виноват тип контейнера.
        val list = try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Индекс не разобрали для метке: ${e.message}")
            return
        }
        val i = list.indexOfFirst { it.code == teacherCode }
        if (i < 0) return
        val t = list[i]
        list[i] = t.copy(
            freshAt = maxOf(t.freshAt, ts),
            pairCount = if (pairCount > 0) pairCount else t.pairCount
        )
        api.saveTeacherIndex(KEY_LIST, GsonHolder.gson.toJson(list))
    }

    /** Свежесть своих пар у конкретного преподавателя, мс. 0 — неизвестно. */
    fun freshAtOf(api: MietApi, teacherCode: String): Long {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return 0L
        return try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java)
                .firstOrNull { it.code == teacherCode }?.freshAt ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Пары преподавателя, собранные из кэша расписаний групп.
     *
     * Одна и та же пара встречается в расписании каждой группы, которую ведёт
     * преподаватель, — поэтому дедуплицируем по (день, неделя, номер пары, группа).
     * Иначе у преподавателя с 5 группами каждая пара показалась бы 5 раз.
     */
    suspend fun lessonsOf(api: MietApi, groups: List<String>, teacherCode: String): List<Lesson> =
        withContext(Dispatchers.IO) {
            val gson = GsonHolder.gson
            val out = LinkedHashMap<String, Lesson>()
            for (g in groups) {
                val raw = api.cachedSchedule(g) ?: continue
                val data: List<Lesson> = try {
                    gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
                } catch (e: Exception) {
                    continue
                }
                for (l in data) {
                    val tf = l.classInfo?.teacherFull ?: continue
                    if (key(tf) != teacherCode) continue
                    val k = "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|${l.group?.name}"
                    out[k] = l
                }
            }
            out.values.sortedWith(
                compareBy({ it.day ?: 1 }, { it.dayNumber ?: 0 }, { it.time?.code ?: 0 })
            )
        }
}