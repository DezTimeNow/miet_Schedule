package com.mietschedule.app

import java.util.Calendar

/**
 * БЛИЖАЙШАЯ ПАРА И ЗАНЯТОСТЬ АУДИТОРИЙ.
 *
 * Две независимые вещи, которые обе упираются в одну нехватку данных: у
 * [Lesson] нет абсолютной даты — только день недели (1..6) и номер пары, а
 * время хранится строкой в [PairTime]. Значит время начала пары можно
 * получить только через таблицу времени, и без неё карточка остаётся с
 * номером, но без часов.
 *
 * Все функции чистые: принимают пары и момент времени, отдают значение.
 * Текущий день выводится из аргумента `now`, а не из Calendar.getInstance()
 * — иначе результат зависел бы от даты запуска теста.
 */
object NextLessonLogic {

    /**
     * Пара вместе со своим временем.
     *
     * Считать время в UI отдельно нельзя: раньше оно вычислялось вторым,
     * независимым проходом по расписанию, и две копии расчёта разъезжались
     * — строка показывала одну дату, а счётчик «через N минут» считался от
     * другой. Здесь время приходит вместе с парой, единым куском.
     */
    data class Hit(
        val lesson: Lesson,
        /** Момент начала в миллисекундах. null — времени нет ни в паре, ни в таблице. */
        val start: Long?,
        /** Момент окончания. null, если таблица времени неполна. */
        val end: Long?,
    )

    /** Момент начала пары по её номеру в таблице времени. */
    fun startMillis(lesson: Lesson, times: List<PairTime>, dayDate: Calendar): Long? {
        val code = lesson.time?.code ?: return null
        val from = times.firstOrNull { it.code == code }?.timeFrom ?: return null
        return atMinutes(dayDate, parseHhMm(from))
    }

    /** Тот же момент, но со временем конца пары. */
    fun endMillis(lesson: Lesson, times: List<PairTime>, dayDate: Calendar): Long? {
        val code = lesson.time?.code ?: return null
        val to = times.firstOrNull { it.code == code }?.timeTo ?: return null
        return atMinutes(dayDate, parseHhMm(to))
    }

    private fun atMinutes(date: Calendar, hm: IntArray?): Long? {
        if (hm == null) return null
        return Calendar.getInstance().apply {
            timeInMillis = date.timeInMillis
            set(Calendar.HOUR_OF_DAY, hm[0])
            set(Calendar.MINUTE, hm[1])
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Вытащить «часы:минуты» из строки времени.
     *
     * Сервер отдаёт время двумя способами: «08:00» и «2026-09-01T08:00:00».
     * Второй длиннее, поэтому ищем вхождение, а не префикс.
     */
    fun parseHhMm(raw: String?): IntArray? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(\\d{1,2}):(\\d{2})").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return intArrayOf(h, min)
    }

    /** Идёт ли пара прямо сейчас. */
    fun isGoing(hit: Hit, now: Long): Boolean {
        val start = hit.start ?: return false
        if (start >= now) return false
        // Времени конца нет у части аудиторий корпуса 8. Тогда считаем пару
        // идущей в пределах 90 минут от начала, иначе строка «сейчас»
        // зависала бы на всех парах без времени.
        val end = hit.end ?: (start + 90L * 60 * 1000)
        return now < end
    }

    /**
     * Пара, которую человек видит в строке «сейчас»: идущая или ближайшая.
     *
     * Идущая пара приоритетнее следующей: если пара идёт сейчас, отдаётся
     * именно она. Это ПРЕЖНЕЕ поведение функции, и менять его нельзя — на нём
     * стоит строка на главном экране.
     *
     * Обёртка над [currentAndNext] отдаёт ту же пару, что и раньше: сначала
     * идущая, иначе ближайшая. Первая попытка возвращала только следующую
     * (`.second`) — и существовавший тест «идущая пара показывается а не
     * следующая» упал сразу. Отсюда явный `?:`, а не `.second`.
     */
    fun next(
        lessons: List<Lesson>,
        times: List<PairTime>,
        now: Long,
        withinDays: Int = 7,
    ): Hit? {
        val (going, upcoming) = currentAndNext(lessons, times, now, withinDays)
        return going ?: upcoming
    }

    /**
     * Идущая пара и ближайшая следующая — обе сразу.
     *
     * Зачем две. Студент смотрит на телефон между парами, и ему нужны оба
     * ответа сразу: «что сейчас» и «что дальше». Одной строки мало: если шла
     * пара, следующая не была видна вовсе, и человек считал, что после этой
     * пары у него ничего нет.
     *
     * Идущая и следующая не могут совпасть: идущая по [isGoing] уже
     * началась, следующая по определению начинается позже [now].
     */
    fun currentAndNext(
        lessons: List<Lesson>,
        times: List<PairTime>,
        now: Long,
        withinDays: Int = 7,
    ): Pair<Hit?, Hit?> {
        if (lessons.isEmpty()) return null to null

        val hits = mutableListOf<Hit>()

        for (dayIn in 0..withinDays) {
            // Прямо от now: так дата не зависит от учебной недели и всегда
            // означает именно тот день, который человек видит в календаре.
            val date = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, dayIn)
            }
            val weekDay = dayIndexFromCalendar(date.get(Calendar.DAY_OF_WEEK))

            for (l in lessons) {
                if ((l.day ?: 1) - 1 != weekDay) continue
                val start = startMillis(l, times, date)
                // Пара без времени таблицы пропускаем: показывать её нечем.
                // Номер пары без часов в строке «сейчас» вводит в заблуждение.
                if (start == null) continue
                hits += Hit(l, start, endMillis(l, times, date))
            }
        }

        // Идущая: началась раньше «сейчас» и ещё не кончилась.
        val going = hits.filter { isGoing(it, now) }.maxByOrNull { it.start ?: 0L }
        // Следующая — строго позже «сейчас». Порог `now`, а не конец идущей:
        // при пересекающихся парах идущая кончается позже следующей, и
        // фильтр по её концу молча проглатывал бы вторую пару.
        val upcoming = hits.filter { (it.start ?: 0L) > now }.minByOrNull { it.start ?: 0L }

        return going to upcoming
    }


    /**
     * Занятость аудитории [room] в указанной паре дня [day].
     *
     * Ответ на вопрос кнопки «Показать свободные»: комната свободна, если
     * в неё не стоит ни одна пара. Сравнение по [roomKey], а не по строке: в
     * списке и в паре имя может отличаться («1201 (м)» и «1201»).
     *
     * @param dayNumberFilter номер учебной недели (DayNumber 0..3) либо null —
     *   не фильтровать. Занятость считается по конкретной учебной неделе:
     *   в «1-й числитель» и «1-й знаменатель» у одной группы разные пары, и
     *   без этого фильтра комната выглядела бы занятой из-за пары, которая
     *   сегодня не проходит.
     */
    fun isRoomBusy(
        room: String,
        lessons: List<Lesson>,
        day: Int,
        pairCode: Int?,
        dayNumberFilter: Int? = null,
    ): Boolean = lessons.any { l ->
        (l.day ?: 1) - 1 == day &&
            (dayNumberFilter == null || (l.dayNumber ?: 0) == dayNumberFilter) &&
            (pairCode == null || (l.time?.code ?: -1) == pairCode) &&
            roomKey(l.room?.name) == roomKey(room)
    }

    /** Корпус аудитории для группировки списка. */
    fun buildingOf(room: String): String =
        com.mietschedule.app.buildingOf(room)
}