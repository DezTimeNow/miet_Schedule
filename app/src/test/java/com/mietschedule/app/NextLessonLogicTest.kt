package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Проверки «ближайшей пары» и занятости аудиторий.
 *
 * Тесты идут по двум причинам. Первая — смысл: функция решает, что показать
 * человеку, и ошибка в ней выглядит как враньё («сейчас 10:40», когда пара
 * в 12:00). Вторая — время здесь берётся из строки разными способами, и
 * именно на этом уже ловились расхождения с сервером.
 *
 * Текущий момент задан явно: если бы он читался из системной даты, проверка
 * в один вторник проходила, а в среду падала.
 */
class NextLessonLogicTest {

    /** Понедельник этой календарной недели, заданное время суток. */
    private fun mondayAt(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) + 5) % 7)
    }.timeInMillis

    /** Плюс [days] суток к моменту. */
    private fun plusDays(base: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

    private fun time(code: Int, from: String, to: String) =
        PairTime(time = "$code пара", code = code, timeFrom = from, timeTo = to)

    private fun lesson(
        day: Int,
        pair: Int,
        name: String = "Предмет",
        room: String? = null,
        weekRow: Int = 0,
    ) = Lesson(
        day = day,
        dayNumber = weekRow,
        time = PairCode(time = "$pair пара", code = pair, timeFrom = null, timeTo = null),
        classInfo = ClassInfo(
            code = "c$pair", name = name,
            teacherFull = "Иванов И.И.", teacher = "Иванов И.И.", form = false,
        ),
        group = GroupInfo(code = "g", name = "ИВТ-11"),
        room = room?.let {
            RoomInfo(code = it.filter { c -> c.isDigit() }.toIntOrNull(), name = it)
        },
    )

    // ─────────────────────── разбор времени ───────────────────────

    @Test
    fun `время разбирается из короткой записи`() {
        assertEquals(listOf(8, 0), NextLessonLogic.parseHhMm("08:00")!!.toList())
    }

    @Test
    fun `время разбирается из полной даты сервера`() {
        assertEquals(listOf(13, 30), NextLessonLogic.parseHhMm("2026-09-01T13:30:00")!!.toList())
    }

    @Test
    fun `пустое время не считается временем`() {
        assertNull(NextLessonLogic.parseHhMm(null))
        assertNull(NextLessonLogic.parseHhMm(""))
        assertNull(NextLessonLogic.parseHhMm("   "))
    }

    @Test
    fun `мусорное время отбрасывается а не падает`() {
        assertNull(NextLessonLogic.parseHhMm("по расписанию"))
        assertNull(NextLessonLogic.parseHhMm("25:00"))
        assertNull(NextLessonLogic.parseHhMm("08:99"))
    }

    // ─────────────────────── ближайшая пара ───────────────────────

    @Test
    fun `находится ближайшая пара дня`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Физика")),
            times = listOf(time(1, "08:00", "09:30"), time(2, "10:00", "11:30")),
            now = mondayAt(9, 0),
        )
        assertNotNull(hit)
        assertEquals("Физика", hit!!.lesson.classInfo?.name)
        // Время приходит вместе с парой — считать его второй раз не нужно.
        assertEquals(10, Calendar.getInstance().apply { timeInMillis = hit.start!! }
            .get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `идущая пара показывается а не следующая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(
                lesson(day = 1, pair = 2, name = "Идущая"),
                lesson(day = 1, pair = 3, name = "Следующая"),
            ),
            times = listOf(time(2, "10:00", "11:30"), time(3, "12:00", "13:30")),
            now = mondayAt(10, 20),
        )
        assertEquals("Идущая", hit?.lesson?.classInfo?.name)
        assertTrue(NextLessonLogic.isGoing(hit!!, mondayAt(10, 20)))
    }

    @Test
    fun `после конца пары показывается следующая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(
                lesson(day = 1, pair = 2, name = "Прошлая"),
                lesson(day = 1, pair = 3, name = "Дальше"),
            ),
            times = listOf(time(2, "10:00", "11:30"), time(3, "12:00", "13:30")),
            now = mondayAt(11, 40),
        )
        assertEquals("Дальше", hit?.lesson?.classInfo?.name)
        assertFalse(NextLessonLogic.isGoing(hit!!, mondayAt(11, 40)))
    }

    @Test
    fun `за минуту до начала пара ещё не идущая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Скоро")),
            times = listOf(time(2, "10:00", "11:30")),
            now = mondayAt(9, 59),
        )
        assertEquals("Скоро", hit?.lesson?.classInfo?.name)
        assertFalse(NextLessonLogic.isGoing(hit!!, mondayAt(9, 59)))
    }

    @Test
    fun `завтрашняя пара получает завтрашнюю дату`() {
        // Пара во вторник. Сегодня понедельник 20:00 — пара завтра.
        // Регрессия: раньше дата считалась через учебную неделю, и «завтра»
        // могло превратиться в дату следующей учебной недели.
        val monday = mondayAt(20, 0)
        val tuesday = plusDays(monday, 1)

        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 2, pair = 1, name = "Завтрашняя")),
            times = listOf(time(1, "08:00", "09:30")),
            now = monday,
        )
        assertNotNull(hit)

        val got = Calendar.getInstance().apply { timeInMillis = hit!!.start!! }
        val want = Calendar.getInstance().apply { timeInMillis = tuesday }
        assertEquals(want.get(Calendar.DAY_OF_MONTH), got.get(Calendar.DAY_OF_MONTH))
        assertEquals(want.get(Calendar.MONTH), got.get(Calendar.MONTH))
    }

    @Test
    fun `прошедшая пара не показывается как прошедшая`() {
        // Пара в четверг, сейчас суббота. Пара должна вернуться, но как
        // СЛЕДУЮЩИЙ четверг, а не как сегодняшний: проверяем именно дату.
        // Раньше дата считалась через учебную неделю, и «ближайшая пара»
        // могла оказаться двумя днями в прошлом.
        val sat = plusDays(mondayAt(12, 0), 5)
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 4, pair = 1, name = "Прошлая")),
            times = listOf(time(1, "08:00", "09:30")),
            now = sat,
        )
        assertNotNull(hit)
        // Начало строго в будущем — то есть это не та минутая пара.
        assertTrue(hit!!.start!! > sat)
        // И это ближайший четверг: через 5 суток, не через 12.
        assertEquals(5L, daysApart(sat, hit.start!!))
    }

    /** Сколько целых суток между двумя моментами. */
    private fun daysApart(from: Long, to: Long): Long {
        fun midnight(v: Long) = Calendar.getInstance().apply {
            timeInMillis = v
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (midnight(to) - midnight(from)) / (24L * 3600 * 1000)
    }

    @Test
    fun `пустое расписание не выдумывает пару`() {
        assertNull(NextLessonLogic.next(emptyList(), emptyList(), mondayAt(9, 0)))
    }

    @Test
    fun `пара без времени таблицы не показывается`() {
        // Часть аудиторий корпуса 8 приходит без Times: номер пары есть,
        // часов нет — показывать её в строке «сейчас» нечем.
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Без времени")),
            times = emptyList(),
            now = mondayAt(9, 0),
        )
        assertNull(hit)
    }

    @Test
    fun `время начала и конца берётся из таблицы`() {
        val l = lesson(day = 1, pair = 2, name = "Физика")
        val day = Calendar.getInstance().apply { timeInMillis = mondayAt(0, 0) }

        val start = NextLessonLogic.startMillis(l, listOf(time(2, "10:15", "11:45")), day)!!
        val gotStart = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(10, gotStart.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, gotStart.get(Calendar.MINUTE))

        val end = NextLessonLogic.endMillis(l, listOf(time(2, "10:15", "11:45")), day)!!
        val gotEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(11, gotEnd.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, gotEnd.get(Calendar.MINUTE))
    }

    // ─────────────────────── занятость аудиторий ───────────────────────

    @Test
    fun `занятая аудитория определяется по паре`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertTrue(NextLessonLogic.isRoomBusy("8307", lessons, day = 1, pairCode = 3))
    }

    @Test
    fun `свободна в другой паре того же дня`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 1, pairCode = 4))
    }

    @Test
    fun `свободна в другой день`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 3, pairCode = 3))
    }

    @Test
    fun `сравнение идёт по roomKey а не по строке`() {
        // В списке «1201 (м)», в паре «1201» — это одна комната.
        val lessons = listOf(lesson(day = 1, pair = 1, room = "1201"))
        assertTrue(NextLessonLogic.isRoomBusy("1201 (м)", lessons, day = 0, pairCode = 1))
    }

    @Test
    fun `суффикс к различает комнаты`() {
        // «8307» и «8307 к» — РАЗНЫЕ аудитории, значит «к» нельзя отбрасывать.
        val lessons = listOf(lesson(day = 1, pair = 1, room = "8307 к"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 0, pairCode = 1))
    }

    @Test
    fun `пустое расписание не бывает занятым`() {
        assertFalse(NextLessonLogic.isRoomBusy("8307", emptyList(), day = 0, pairCode = 1))
    }

    @Test
    fun `пара чужой учебной недели не занимает аудиторию`() {
        // Регрессия: без фильтра по DayNumber аудитория считалась занятой
        // из-за пары, которая сегодня не проходит.
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307", weekRow = 2))
        assertTrue(NextLessonLogic.isRoomBusy("8307", lessons, 1, 3, dayNumberFilter = 2))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, 1, 3, dayNumberFilter = 0))
    }

    @Test
    fun `корпус берётся по первой цифре`() {
        assertEquals("Корпус 3", NextLessonLogic.buildingOf("3205"))
        assertEquals("Корпус 8", NextLessonLogic.buildingOf("8307"))
    }
}