package com.mietschedule.app

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Определение текущей учебной недели: 1-й числитель / 1-й знаменатель / 2-й числитель / 2-й знаменатель.
 *
 * Алгоритм взят с самого сайта miet.ru (его бандл `getWeekTypeNum`):
 *   weekType = (startOfWeek(сегодня) − startOfWeek(semestr_start)) в неделях % 4
 * где startOfWeek — понедельник, а semestr_start публикуется на странице расписания
 * как глобальная переменная `semestr_start`.
 *
 * 0 → 1-й числитель     1 → 1-й знаменатель
 * 2 → 2-й числитель     3 → 2-й знаменатель
 */
object WeekType {

    /**
     * Дата начала семестра. Сайт отдаёт её в HTML страницы расписания как
     * `semestr_start = "2026-08-04"`. Значение ниже проверено 01.10.2026 —
     * совпадает с тем, что показывает сайт. Если сервер поменяет семестр,
     * его нужно обновить здесь (или получать динамически).
     */
    const val SEMESTR_START_ISO = "2026-08-04"

    private val NAMES = arrayOf(
        "1-й числитель",
        "1-й знаменатель",
        "2-й числитель",
        "2-й знаменатель"
    )

    /** Тип недели: 0..3 */
    fun current(startIso: String = SEMESTR_START_ISO): Int {
        val start = parseIso(startIso) ?: return 0
        return typeFor(today(), start)
    }

    fun name(type: Int): String = NAMES.getOrElse(type) { "" }

    /** Исходный расчёт: разница понедельников в неделях, по модулю 4. */
    fun typeFor(date: Calendar, semestrStart: Calendar): Int {
        val a = startOfWeek(date.timeInMillis)
        val b = startOfWeek(semestrStart.timeInMillis)
        // ВАЖНО: считаем в днях, а не в миллисекундах. Разница во времени
        // упирается в Long, но .toInt() на ней переполняется: за 56 дней
        // это 4 838 400 000 мс при Int.MAX = 2 147 483 647, из-за чего
        // неделя вычислялась как 2-й знаменатель вместо 1-го числителя.
        val days = TimeUnit.MILLISECONDS.toDays(a - b)
        return Math.floorMod((days / 7).toInt(), 4)
    }

    /** Понедельник 00:00 локального времени для указанного момента. */
    private fun startOfWeek(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Calendar.SUNDAY = 1, MONDAY = 2 → смещение, чтобы неделя начиналась с понедельника
        val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(Calendar.DAY_OF_MONTH, -offset)
        return cal.timeInMillis
    }

    fun today(): Calendar = Calendar.getInstance()

    /** Разбирать "2026-08-04" в Calendar (00:00 локального времени). */
    fun parseIso(iso: String): Calendar? = runCatching {
        val parts = iso.trim().split("-")
        require(parts.size == 3)
        Calendar.getInstance().apply {
            clear()
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
    }.getOrNull()

    /**
     * Номер строки `DayNumber` в ответе API, соответствующий текущей неделе.
     *
     * В ответе `DayNumber` идёт 0..3 и означает **конкретную учебную неделю**:
     * 0 → 1-й числитель, 1 → 1-й знаменатель, 2 → 2-й числитель, 3 → 2-й знаменатель.
     * Это НЕ «чёт = числитель»: например у ИВТ-11 строки 0 и 2 различаются
     * (4-я пара — «Линейная алгебра» против «Физика. Механика»), значит
     * фильтровать по чётности нельзя — надо брать ровно одну строку.
     * Проверено на 25 группах: у всех weekType 0 и 2 иногда совпадают (дубли),
     * а у ИВТ-11/ПИН-11/Д-11 различаются.
     */
    fun currentRowIndex(startIso: String = SEMESTR_START_ISO): Int = current(startIso)

    /** Человекочитаемое описание: «1-й числитель» и т.д. */
    fun currentName(startIso: String = SEMESTR_START_ISO): String = name(current(startIso))

    /** Ближайшие недели вперёд для подсказок вида «через неделю — знаменатель». */
    fun nextName(startIso: String = SEMESTR_START_ISO): String = name((current(startIso) + 1) % 4)

    /** Ближайшая дата, когда тип недели сменится. */
    fun nextChangeMillis(startIso: String = SEMESTR_START_ISO): Long {
        val start = parseIso(startIso) ?: return 0L
        val cal = today()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        return cal.timeInMillis
    }

    /** Время в локальной таймзоне (нужно для unit-тестов на машине без локали). */
    fun tz(): TimeZone = TimeZone.getDefault()
}
