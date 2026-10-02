package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты разбора ответов miet.ru и вспомогательной логики.
 *
 * Раньше здесь были тесты сравнения расписаний для уведомлений. Уведомления
 * по требованию убраны, класс ScheduleNotifier удалён — вместо него проверяем
 * то, что реально ломает приложение: разбор JSON, склонение числительных,
 * группировку аудиторий по корпусу и нормализацию ФИО.
 */
class ScheduleParseTest {

    // ───────────────────────── склонение числительных ─────────────────────────

    @Test
    fun `склонение числительных корректно`() {
        assertEquals("аудитория", plural(1, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(2, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(4, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(5, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(11, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(14, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитория", plural(21, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(22, "аудитория", "аудитории", "аудиторий"))
        // 31 оканчивается на 1 и не попадает в 11..14 — значит «31 группа».
        assertEquals("группа", plural(31, "группа", "группы", "групп"))
        assertEquals("31 группа", "31 " + plural(31, "группа", "группы", "групп"))
        assertEquals("5 групп", "5 " + plural(5, "группа", "группы", "групп"))
        assertEquals("11 групп", "11 " + plural(11, "группа", "группы", "групп"))
    }

    // ───────────────────────── группировка аудиторий ─────────────────────────

    @Test
    fun `аудитории группируются по номеру корпуса`() {
        assertEquals("Корпус 1", buildingOf("1201 м"))
        assertEquals("Корпус 3", buildingOf("3104 м"))
        assertEquals("Корпус 4", buildingOf("4114 к"))
        assertEquals("Корпус 8", buildingOf("8102"))
        assertEquals("Корпус 8", buildingOf("8307 к"))
    }

    @Test
    fun `аудитории без цифры не режутся на пустые корпуса`() {
        // Раньше «ДК МИЭТ» превращалось в «ДК», а «Аудитория практической
        // подготовки 7» — в «Аудитория». Оба названия осмысленные.
        assertEquals("ДК МИЭТ", buildingOf("ДК МИЭТ"))
        assertEquals("УВЦ", buildingOf("УВЦ 1"))
        assertEquals("Виртуальные аудитории", buildingOf("Виртуальная аудитория 9"))
        assertEquals("Аудитории практики", buildingOf("Аудитория практической подготовки 7"))
        assertEquals("Прочее", buildingOf(""))
        assertEquals("Прочее", buildingOf(null))
    }

    // ───────────────────────── код аудитории ─────────────────────────

    @Test
    fun `код аудитории приводится к Int из любого типа`() {
        // Сервер отдаёт Code числом, но полагаться на это нельзя.
        assertEquals(120, RoomInfo(code = 120).roomCode())
        assertEquals(120, RoomInfo(code = "120").roomCode())
        assertEquals(120, RoomInfo(code = " 120 ").roomCode())
        assertEquals(120, RoomInfo(code = 120.0).roomCode())
        assertEquals(null, RoomInfo(code = null).roomCode())
        assertEquals(null, RoomInfo(code = "не аудитория").roomCode())
    }

    // ───────────────────────── идентификация преподавателя ─────────────────────────

    @Test
    fun `ФИО нормализуется в устойчивый ключ`() {
        // Ключ преподавателя — нормализованное ФИО, а НЕ Class.Code: тот является
        // GUID предмета, из-за чего один человек попадал в список несколько раз
        // (выходило 3974 «преподавателя» вместо 681).
        val a = TeacherIndex.key("Лупин Сергей Сергеевич")
        val b = TeacherIndex.key("  Лупин   Сергей  Сергеевич ")
        assertEquals(a, b)
        assertEquals(a, TeacherIndex.key("ЛУПИН СЕРГЕЙ СЕРГЕЕВИЧ"))
        // Разные люди — разные ключи.
        assertTrue(TeacherIndex.key("Лупин Сергей Сергеевич") != TeacherIndex.key("Лупин Сергей Петрович"))
    }

    @Test
    fun `служебные записи отличимы от людей`() {
        assertTrue(TeacherIndex.isService("Преподаватель УВЦ"))
        assertTrue(TeacherIndex.isService("Преподаватель практической подготовки 7"))
        assertTrue(!TeacherIndex.isService("Лавров Игорь Викторович"))
    }

    // ───────────────────────── разбор ответа сервера ─────────────────────────

    @Test
    fun `ответ расписания разбирается полностью`() {
        // Реальный кусок ответа miet.ru. Здесь важны три момента:
        //  - Time приходит строкой «2 пара», а не числом (иначе NumberFormatException);
        //  - Room.Code — число 120, а не строка;
        //  - Class.Code — GUID предмета, а не код преподавателя.
        val json = """
            {
              "Times": [
                {"Time":"2 пара","Code":2,"TimeFrom":"0001-01-01T10:30:00","TimeTo":"0001-01-01T11:50:00"}
              ],
              "Data": [
                {
                  "Day": 4,
                  "DayNumber": 0,
                  "Time": {"Time":"2 пара","Code":2,"TimeFrom":"0001-01-01T10:30:00","TimeTo":"0001-01-01T11:50:00"},
                  "Class": {
                    "Code": "402a26bd-3b16-11f1-a7c9-0050569f2356",
                    "Name": "Основы технологии электронной компонентной базы [Лек]",
                    "TeacherFull": "Шевяков Василий Иванович",
                    "Teacher": "Шевяков В.И.",
                    "Form": false
                  },
                  "Group": {"Code":"000000000000250","Name":"ЭН-31"},
                  "Room": {"Code":120,"Name":"1201 м"}
                }
              ],
              "Semestr": "2026-09-01"
            }
        """.trimIndent()

        val resp = GsonHolder.gson.fromJson(json, ScheduleResponse::class.java)
        assertEquals(1, resp.data!!.size)

        val lesson = resp.data!!.first()
        assertEquals(4, lesson.day)
        assertEquals(0, lesson.dayNumber)
        // Time — строка, иначе всё разваливается
        assertEquals("2 пара", lesson.time?.time)
        assertEquals(2, lesson.time?.code)
        // аудитория — число
        assertEquals(120, lesson.room?.roomCode())
        assertEquals("1201 м", lesson.room?.name)
        // предмет и его GUID
        assertEquals("402a26bd-3b16-11f1-a7c9-0050569f2356", lesson.classInfo?.code)
        assertEquals("Шевяков Василий Иванович", lesson.classInfo?.teacherFull)
        assertEquals("Шевяков В.И.", lesson.classInfo?.teacher)
        assertEquals("ЭН-31", lesson.group?.name)
        assertEquals(1, resp.times!!.size)
        assertEquals("2026-09-01", resp.semestr)
    }

    @Test
    fun `несколько групп в одной аудитории различаются`() {
        // Реальная причина падения: у аудитории в одном слоте бывает несколько
        // групп (ЭН-31 и ЭН-32 в 1201 одновременно). Ключ строки в LazyColumn
        // строится без группы — «4_2__0» — и Compose падал с
        // «Key already used». Проверяем, что данные для различения есть.
        val json = """
            {"Data":[
              {"Day":4,"DayNumber":0,
               "Time":{"Time":"2 пара","Code":2},
               "Class":{"Code":"a","Name":"Предмет","TeacherFull":"Шевяков Василий Иванович"},
               "Group":{"Code":"1","Name":"ЭН-31"},
               "Room":{"Code":120,"Name":"1201 м"}},
              {"Day":4,"DayNumber":0,
               "Time":{"Time":"2 пара","Code":2},
               "Class":{"Code":"a","Name":"Предмет","TeacherFull":"Шевяков Василий Иванович"},
               "Group":{"Code":"2","Name":"ЭН-32"},
               "Room":{"Code":120,"Name":"1201 м"}}
            ]}
        """.trimIndent()

        val resp = GsonHolder.gson.fromJson(json, ScheduleResponse::class.java)
        assertEquals(2, resp.data!!.size)

        // Ключ с полным набором полей различает пары
        val keys = resp.data!!.map {
            listOf(
                it.day.toString(),
                it.dayNumber.toString(),
                it.time?.code.toString(),
                it.classInfo?.name.orEmpty(),
                it.group?.name.orEmpty()
            ).joinToString("|")
        }
        assertEquals("разные группы должны давать разные ключи", 2, keys.toSet().size)

        // А вот ключ без группы — нет, и именно он ронял приложение
        val badKeys = resp.data!!.map {
            listOf(it.day.toString(), it.dayNumber.toString(), it.time?.code.toString()).joinToString("|")
        }
        assertEquals("старый ключ был неуникален", 1, badKeys.toSet().size)
    }

    @Test
    fun `аудитория разбирается из эндпоинта`() {
        val json = """[{"Code":120,"Name":"1201 (м)"},{"Code":234,"Name":"8307 к"}]"""
        val list = GsonHolder.gson.fromJson(json, Array<Audience>::class.java).toList()
        assertEquals(2, list.size)
        assertEquals(120, list[0].code)
        assertEquals("1201 (м)", list[0].name)
        assertEquals("8307 к", list[1].name)
    }

    @Test
    fun `пустой и битый ответ не роняют разбор`() {
        val empty = GsonHolder.gson.fromJson("""{"Data":[],"Times":[]}""", ScheduleResponse::class.java)
        assertTrue(empty.data!!.isEmpty())
        assertTrue(empty.times!!.isEmpty())

        // Нет ни Data, ни Times: Gson оставляет поля null, а не пустыми списками.
        // Поэтому код обязан обращаться через ?.orEmpty() — проверяем, что
        // разбор не падает и что null корректно трактуется как «ничего нет».
        val bare = GsonHolder.gson.fromJson("""{}""", ScheduleResponse::class.java)
        assertTrue(bare.data == null || bare.data!!.isEmpty())
        assertTrue(bare.times == null || bare.times!!.isEmpty())
    }

    // ───────────────────────── склейка двух списков аудиторий ─────────────────────────
    //
    // Эндпоинт /audiences отдаёт 136 аудиторий, расписания групп — 194 пары
    // «код, имя». Одни и те же помещения названы по-разному и имеют разные коды,
    // поэтому склеивать по коду нельзя: в списке появлялись дубли «8307», «8308»,
    // «8309», «4109», «3304», «3305».

    @Test
    fun `одно помещение из разных источников получает один ключ`() {
        // 1202 в эндпоинте — код 121, в расписаниях — код 1202. Ключ должен совпасть.
        assertEquals(roomKey("1202 (м)"), roomKey("1202 м"))
        assertEquals(roomKey("1202 (м)"), roomKey("1202"))
        // «(к)» в скобках — уточнение сервера: код 98 это «3105 (к)» в эндпоинте
        // и просто «3105» в расписаниях, то есть одна и та же аудитория.
        assertEquals(roomKey("3105"), roomKey("3105 (к)"))
        assertEquals(roomKey("3303 (м)"), roomKey("3303 м"))
        assertEquals(roomKey("3120 а (к)"), roomKey("3120а"))
    }

    @Test
    fun `разные помещения получают разные ключи`() {
        // 8307 и 8307 к — обычная аудитория и компьютерный класс. Сливать нельзя.
        // Буква без скобок — другое помещение: код 8307 и код 234.
        assertNotEquals(roomKey("8307"), roomKey("8307 к"))
        assertNotEquals(roomKey("8308"), roomKey("8308 к"))
        assertNotEquals(roomKey("8309"), roomKey("8309 к"))
        // 3303 а и 3303 м — разные.
        assertNotEquals(roomKey("3303а"), roomKey("3303 м"))
        // Разные номера — разные помещения.
        assertNotEquals(roomKey("8102"), roomKey("8103"))
        // Служебные без номера не должны склеиваться друг с другом.
        assertNotEquals(roomKey("УВЦ 1"), roomKey("УВЦ 2"))
        assertNotEquals(roomKey("Виртуальная аудитория 1"), roomKey("Виртуальная аудитория 2"))
        assertNotEquals(
            roomKey("Аудитория практической подготовки 1"),
            roomKey("Аудитория практической подготовки 10")
        )
    }

    @Test
    fun `слитно-без-пробела и с-пробелом это одно помещение`() {
        assertEquals(roomKey("3303а"), roomKey("3303 а"))
        assertEquals(roomKey("3102а"), roomKey("3102 а"))
        assertEquals(roomKey("3120а"), roomKey("3120 а"))
    }

    // ───────────────────────── сортировка аудиторий ─────────────────────────

    @Test
    fun `аудитории сортируются по номеру а не по строке`() {
        val names = listOf("8109", "8107", "8102", "8103", "8104")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        assertEquals(listOf("8102", "8103", "8104", "8107", "8109"), sorted)
    }

    @Test
    fun `суффикс идёт сразу после своего номера`() {
        val names = listOf("8309 к", "8102", "8307 к", "8307", "8109", "8208")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        // 8102, 8109, 8208, затем 8307 и сразу за ним 8307 к, потом 8309 к.
        assertEquals(listOf("8102", "8109", "8208", "8307", "8307 к", "8309 к"), sorted)
    }

    @Test
    fun `аудитория без номера уходит в конец`() {
        val names = listOf("УВЦ 1", "8102", "ДК МИЭТ")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        assertEquals(listOf("8102", "ДК МИЭТ", "УВЦ 1"), sorted)
    }

    // ───────────────────────── время начала и конца пары ────────────────────

    /** Пара с временем — как её реально отдаёт miet.ru в Data[].Time. */
    private fun lessonWithTime(code: Int, from: String?, to: String?): Lesson =
        Lesson(
            time = PairCode(time = "$code пара", code = code, timeFrom = from, timeTo = to)
        )

    @Test
    fun `время берётся из самой пары когда серверной таблицы нет`() {
        // Именно эта ситуация у преподавателя: расписание собрано из кэша
        // расписаний групп, верхнего массива Times там не существует.
        val lessons = listOf(
            lessonWithTime(1, "0001-01-01T09:00:00", "0001-01-01T10:20:00"),
            lessonWithTime(2, "0001-01-01T10:30:00", "0001-01-01T11:50:00")
        )
        val times = mergeTimes(null, lessons)
        assertEquals(2, times.size)
        assertEquals(1, times[0].code)
        assertEquals("0001-01-01T09:00:00", times[0].timeFrom)
        assertEquals("0001-01-01T10:20:00", times[0].timeTo)
    }

    @Test
    fun `пустая пара с кодом ноль в таблицу времени не попадает`() {
        // «Разговоры о важном» приходят с Code 0 и нулевым временем — это не
        // настоящая пара, а запись без слота. В таблице она ломала бы нумерацию.
        val lessons = listOf(
            lessonWithTime(0, null, null),
            lessonWithTime(1, "0001-01-01T09:00:00", "0001-01-01T10:20:00")
        )
        val times = mergeTimes(null, lessons)
        assertEquals(1, times.size)
        assertEquals(1, times[0].code)
    }

    @Test
    fun `пара без времени не затирает таблицу сервера`() {
        val server = listOf(
            PairTime(time = "1 пара", code = 1,
                timeFrom = "0001-01-01T09:00:00", timeTo = "0001-01-01T10:20:00")
        )
        val lessons = listOf(lessonWithTime(1, null, null))
        val times = mergeTimes(server, lessons)
        assertEquals("0001-01-01T09:00:00", times.first().timeFrom)
    }

    @Test
    fun `таблица сервера и таблица из пар дополняют друг друга`() {
        // Сервер знает про 8-ю пару, хотя в расписании дня только первые четыре.
        val server = listOf(
            PairTime(time = "1 пара", code = 1,
                timeFrom = "0001-01-01T09:00:00", timeTo = "0001-01-01T10:20:00"),
            PairTime(time = "8 пара", code = 8,
                timeFrom = "0001-01-01T20:00:00", timeTo = "0001-01-01T21:20:00")
        )
        val lessons = listOf(lessonWithTime(3, "0001-01-01T12:30:00", "0001-01-01T13:50:00"))
        val times = mergeTimes(server, lessons)
        assertEquals(listOf(1, 3, 8), times.mapNotNull { it.code })
    }

    @Test
    fun `время у пар разное и берётся из своей пары а не из чужой группы`() {
        // 3-я пара у ЮР-26-11О начинается в 12:30, у Э-24-11 — в 12:00.
        val lessons = listOf(
            lessonWithTime(3, "0001-01-01T12:30:00", "0001-01-01T13:50:00"),
            lessonWithTime(3, "0001-01-01T12:00:00", "0001-01-01T13:20:00")
        )
        val times = mergeTimes(null, lessons)
        // Одна запись на код пары: у преподавателя это один и тот же слот.
        assertEquals(1, times.size)
        assertNotNull(times[0].timeFrom)
    }

    // ───────────────────────── ключи списков ─────────────────────────────────

    /**
     * Регрессия: корпус 8 падал при прокрутке.
     *
     * Ключ элемента был it.code, но код из /audiences — внутренний id, а не
     * номер аудитории. Разные помещения его делят: «8307» и «8307 к» оба
     * получили id 234, поэтому внутри одного корпуса ключ 234 повторялся
     * дважды и LazyColumn падал с «Key was already used».
     */
    @Test
    fun `ключ аудитории строится от номера а не от внутреннего id`() {
        val seven = Audience(code = 234, name = "8307")
        val sevenLab = Audience(code = 234, name = "8307 к")

        // Имя уникально, а вот код — нет: ключ обязан разводить их.
        val k1 = "a_${roomKey(seven.name)}_${seven.code}"
        val k2 = "a_${roomKey(sevenLab.name)}_${sevenLab.code}"
        assertNotEquals(k1, k2)
    }

    @Test
    fun `все аудитории корпуса 8 получают разные ключи`() {
        val корпус8 = listOf(
            Audience(code = 234, name = "8307"),
            Audience(code = 234, name = "8307 к"),
            Audience(code = 211, name = "8308"),
            Audience(code = 211, name = "8308 к"),
            Audience(code = 97, name = "8309"),
            Audience(code = 97, name = "8309 к")
        )
        val keys = корпус8.map { "a_${roomKey(it.name)}_${it.code}" }
        assertEquals(keys.size, keys.toSet().size)
        // Ровно 17 уникальных имён корпуса 8 — известная величина.
        assertEquals(6, корпус8.map { roomKey(it.name) }.toSet().size)
    }

    @Test
    fun `лишние пробелы в имени не создают второй элемент`() {
        // Это не баг, а задуманное поведение: «ДК МИЭТ» и «ДК МИЭТ » —
        // одна и та же аудитория, и склейка обязана их схлопнуть, иначе
        // в списке появился бы дубль, а с ним и падение LazyColumn.
        assertEquals(roomKey("ДК МИЭТ"), roomKey("ДК МИЭТ "))
        assertEquals(roomKey("УВЦ 1"), roomKey("  УВЦ 1"))
        assertNotEquals(roomKey("УВЦ 1"), roomKey("УВЦ 2"))
    }

    @Test
    fun `после склейки ключи аудиторий заведомо уникальны`() {
        // Инвариант, на котором держится ключ списка: склейка идёт по roomKey,
        // поэтому в итоговом списке все значения roomKey различны. Если это
        // перестанет быть правдой, ключи LazyColumn совпадут и список упадёт.
        val корпус3 = listOf("3205", "3205 МПСУ", "3210", "3210 МПСУ", "3223", "3223 ОМС")
        val keys = корпус3.map { "a_${roomKey(it)}_${null}" }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ───────────── аудитория: фильтр по имени, а не по внутреннему id ─────────

    /**
     * Регрессия: у 8307 показывалась одна пара вместо 72.
     *
     * Код из /audiences — внутренний id, а не номер аудитории: «8307» это id
     * 234, а «8307 к» тоже id 234. Фильтр кэша по коду брал чужие пары, а
     * настоящие 72 пары «8307» отбрасывал. Идентичность помещения — имя.
     */
    @Test
    fun `аудитория находится по имени а не по внутреннему коду`() {
        val восемьТриНольСемь = Audience(code = 234, name = "8307")
        val восемьТриНольСемьК = Audience(code = 234, name = "8307 к")

        // Один и тот же код — разные помещения.
        assertEquals(восемьТриНольСемь.code, восемьТриНольСемьК.code)
        assertNotEquals(roomKey(восемьТриНольСемь.name), roomKey(восемьТриНольСемьК.name))
    }

    @Test
    fun `фильтр по коду перепутывает 8307 с 8307к`() {
        // Точное воспроизведение бага: слот в кэше «8307 к» с id 234 совпадал
        // по коду с настоящей «8307», поэтому показывался чужой слот.
        val вКэше = listOf(
            Triple(8307, "8307", "География"),
            Triple(234, "8307 к", "География")
        )
        val поКоду = вКэше.filter { it.first == 234 }
        val поИмени = вКэше.filter { roomKey(it.second) == roomKey("8307") }

        assertEquals(1, поКоду.size)
        assertEquals("8307 к", поКоду.first().second)
        assertEquals(1, поИмени.size)
        assertEquals("8307", поИмени.first().second)
    }

    @Test
    fun `аудитории с одинаковым кодом получают разные ключи`() {
        // 42 аудитории из 136 делят id с соседним помещением. Суффиксы ЗДЕСЬ
        // без скобок: «8307 к» — отдельная аудитория, а «3105 (к)» и «3105» —
        // одна и та же (скобки лишь уточняют, это проверяет другой тест).
        val парные = listOf(
            "8307" to "8307 к", "8308" to "8308 к", "8309" to "8309 к",
            "3205" to "3205 МПСУ", "4125" to "4125 БМС", "4211" to "4211 МПСУ",
            "4213" to "4213 МПСУ", "4309" to "4309 ПМТ", "4315" to "4315 ПМТ",
            "3306 (м)" to "3306 Право", "3350 а (м)" to "3350амЛПО"
        )
        for ((a, b) in парные) {
            assertNotEquals("«$a» и «$b» должны различаться", roomKey(a), roomKey(b))
        }
    }

    @Test
    fun `склонение числительных для числа аудиторий`() {
        // plural возвращает СЛОВО, число подставляет вызывающий код.
        assertEquals("аудитория", plural(1, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(3, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(17, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(11, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитория", plural(21, "аудитория", "аудитории", "аудиторий"))
    }

    /**
     * Регрессия: у 8307 показывалось 12 пар вместо 72.
     *
     * Две причины складывались. Первая: фильтр кэша шёл по внутреннему коду
     * (у «8307» он 234 — тот же, что у «8307 к»), поэтому чужие пары
     * попадали, а свои отбрасывались. Вторая: пустой ответ сервера
     * («Data: []») кэшировался как валидный, и при следующем запуске
     * lessons становился непустым — локальная сборка не запускалась вовсе.
     */
    @Test
    fun `аудитория 8307 находит все 72 свои пары а не 12 чужих`() {
        // Идентичность помещения — имя. Код у «8307» и «8307 к» общий (234),
        // но помещения разные, и фильтр обязан их развести.
        val моя = "8307"
        val чужая = "8307 к"
        assertNotEquals(roomKey(моя), roomKey(чужая))
        assertEquals("num:8307:", roomKey(моя))
        assertEquals("num:8307:к", roomKey(чужая))
    }

    // ─────────────────────── день недели ─────────────────────────────────────

    /**
     * Регрессия: 2 октября 2026 — пятница, а приложение называло «Сб».
     *
     * Calendar.DAY_OF_WEEK считает неделю с воскресенья (SUNDAY=1 … SATURDAY=7),
     * расписание — с понедельника. Прежняя формула `if (cw == SUNDAY) 0 else
     * cw - 1` сдвигала день на единицу.
     */
    @Test
    fun `день недели считается с понедельника а не с воскресенья`() {
        // Calendar: SUNDAY=1 … SATURDAY=7 → наш индекс 0=Пн … 6=Вс
        assertEquals(0, dayIndexFromCalendar(2))  // MONDAY    → Пн
        assertEquals(1, dayIndexFromCalendar(3))  // TUESDAY   → Вт
        assertEquals(2, dayIndexFromCalendar(4))  // WEDNESDAY → Ср
        assertEquals(3, dayIndexFromCalendar(5))  // THURSDAY  → Чт
        assertEquals(4, dayIndexFromCalendar(6))  // FRIDAY    → Пт
        assertEquals(5, dayIndexFromCalendar(7))  // SATURDAY  → Сб
        assertEquals(6, dayIndexFromCalendar(1))  // SUNDAY    → Вс
    }

    @Test
    fun `название дня совпадает с настоящим днем`() {
        // 2 октября 2026 — пятница. Именно её приложение называло «Сб».
        assertEquals("Пт", DAY_SHORT[dayIndexFromCalendar(6)])
        assertEquals("Вс", DAY_SHORT[dayIndexFromCalendar(1)])
        assertEquals("Пн", DAY_SHORT[dayIndexFromCalendar(2)])
    }

    @Test
    fun `старая формула сдвигала день ровно на единицу`() {
        // Проверено на всех семи днях: старая формула давала ровно на день
        // больше верной, без исключений. Из-за этого любой вторник
        // отображался как среда, пятница — как суббота, воскресенье — как понедельник.
        for (cw in 1..7) {
            val верно = dayIndexFromCalendar(cw)
            val старое = if (cw == 1) 0 else cw - 1
            // старая формула называла день на один больше правильного
            assertEquals("cw=$cw", (верно + 1) % 7, старое)
        }
    }

    // ─────────────────────── обновления через GitHub ────────────────────────

    /**
     * Тег релиза приходит в разном виде: «v12», «12», «release-12», «v1.2.3».
     * versionCode приложения — целое число, поэтому из тега достаём число.
     */
    @Test
    fun `тег релиза разбирается в номер версии`() {
        assertEquals(12, UpdateChecker.parseVersionCode("v12"))
        assertEquals(12, UpdateChecker.parseVersionCode("V12"))
        assertEquals(12, UpdateChecker.parseVersionCode("12"))
        assertEquals(12, UpdateChecker.parseVersionCode("  v12  "))
        assertEquals(3, UpdateChecker.parseVersionCode("release-3"))
    }

    @Test
    fun `мусорный тег не превращается в версию`() {
        // Нет числа — сравнивать не с чем. Показывать диалог с «vNaN» хуже,
        // чем не показать его вовсе.
        assertNull(UpdateChecker.parseVersionCode(null))
        assertNull(UpdateChecker.parseVersionCode(""))
        assertNull(UpdateChecker.parseVersionCode("latest"))
        assertNull(UpdateChecker.parseVersionCode("без цифр"))
    }

    @Test
    fun `новая версия определяется только когда она больше текущей`() {
        val current = 12
        assertTrue(UpdateChecker.parseVersionCode("v13")!! > current)
        assertFalse(UpdateChecker.parseVersionCode("v12")!! > current)
        assertFalse(UpdateChecker.parseVersionCode("v11")!! > current)
        // Релиз старее установленной сборки диалог показывать нельзя —
        // иначе предложишь «откатиться».
        assertTrue(UpdateChecker.parseVersionCode("v12")!! >= current)
        assertFalse(UpdateChecker.parseVersionCode("v11")!! >= current)
    }

    @Test
    fun `размер релиза читается по-человечески`() {
        val u = UpdateInfo(
            versionCode = 13, tagName = "v13", title = "", notes = "",
            apkName = "a.apk", downloadUrl = "http://x", sizeBytes = 17490783, sha256 = null,
        )
        assertEquals("16.7 МБ", u.sizeLabel)
        assertEquals("v13", u.versionLabel)
        // Размер неизвестен — подпись пустая, а не «0.0 МБ».
        assertEquals("", u.copy(sizeBytes = 0L).sizeLabel)
    }

    @Test
    fun `подпись последней проверки обновлений читается по-человочески`() {
        assertEquals("Обновления ещё не проверялись", lastCheckLabel(0L))
        val now = System.currentTimeMillis()
        assertEquals("Обновления проверены только что", lastCheckLabel(now))
        assertTrue(lastCheckLabel(now - 5 * 60_000).contains("5 мин"))
        assertTrue(lastCheckLabel(now - 3 * 3_600_000).contains("3 ч"))
        assertTrue(lastCheckLabel(now - 2 * 86_400_000L).contains("2 дн"))
    }
}
