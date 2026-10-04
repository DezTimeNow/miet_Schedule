package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки навигации и состояния.
 *
 * Логика переходов вынесена в чистые функции `startScreenFor` и
 * `backTargetFor`, поэтому проверяется напрямую, без запуска Activity.
 * Именно эта логика ломалась трижды: старт открывал список групп вместо
 * расписания, «Избранное» без выбранной группы вело в `SCHEDULE` и падало
 * на `selection!!`, а правило переходов было продублировано в трёх местах
 * и разошлось между ними.
 */
class NavigationAndStateTest {

    // ── Стартовый экран ─────────────────────────────────────────────────

    @Test
    fun `без сохранённого выбора открывается выбор роли`() {
        assertEquals(Screen.PICK_ROLE, startScreenFor(false, false))
    }

    @Test
    fun `сохранённая группа открывает сразу расписание`() {
        assertEquals(Screen.SCHEDULE, startScreenFor(true, false))
    }

    @Test
    fun `группа переданная извне открывает расписание`() {
        assertEquals(Screen.SCHEDULE, startScreenFor(false, true))
    }

    // ── Системная кнопка «Назад» ────────────────────────────────────────

    @Test
    fun `с выбора группы назад ведёт на выбор роли`() {
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.PICK_ENTITY, hasSelection = true))
    }

    @Test
    fun `с расписания назад ведёт на выбор сущности`() {
        assertEquals(Screen.PICK_ENTITY, backTargetFor(Screen.SCHEDULE, hasSelection = true))
    }

    @Test
    fun `с расписания без выбора назад ведёт на выбор роли`() {
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.SCHEDULE, hasSelection = false))
    }

    @Test
    fun `из избранного назад ведёт в главное меню`() {
            // Исправление жалобы владельца: из избранного назад вёл на расписание
            // при сохранённой группе, то есть ПО НАПРАВЛЕНИЮ стрелки уводил
            // вперёд — на тот экран, из которого в избранное и пришли.
            // Откуда открыли избранное, там и стоит кнопка «Избранное».
            assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.FAVORITES, hasSelection = true))
    }

    @Test
    fun `из избранного без выбора назад ведёт на выбор роли`() {
        // Регрессия: отсюда шли в SCHEDULE при пустом выборе и падали.
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.FAVORITES, hasSelection = false))
    }

    @Test
    fun `из избранного открытое расписание возвращает назад в избранное`() {
            // Полный круг: меню → избранное → расписание → назад → избранное.
            // Без этого расписание помнило только, что группа выбрана, и назад
            // уводил в список групп, откуда человек в избранное не заходил.
            assertEquals(
            Screen.FAVORITES,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.FAVORITES),
        )
    }

    @Test
    fun `свое расписание возвращает назад на выбор сущности а не в избранное`() {
        // Происхождение важно: то же расписание, открытое НЕ из избранного,
        // обязано вести назад по-старому — в выбор группы или аудитории.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.PICK_ENTITY),
        )
    }

    @Test
    fun `расписание открытое из блока меню возвращает назад в меню`() {
        // Блок «сейчас и дальше» стоит в главном меню, и назад с открытого по
        // нему расписания обязан вести в меню. Проверено на эмуляторе: раньше
        // уводило в список групп, потому что стрелка «‹» шла напрямую в
        // PICK_ENTITY в обход backTargetFor.
        assertEquals(
            Screen.PICK_ROLE,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.PICK_ROLE),
        )
    }

    @Test
    fun `происхождение не мешает обычному переходу`() {
        // origin == SCHEDULE — вырожденный случай (расписание открыло расписание).
        // Такого не бывает, но функция не должна уводить на сам экран: иначе
        // назад зациклился бы на месте.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.SCHEDULE),
        )
    }

    @Test
    fun `из программы с выбором назад ведёт на выбор сущности`() {
        assertEquals(Screen.PICK_ENTITY, backTargetFor(Screen.ABOUT, hasSelection = true))
    }

    @Test
    fun `из программы без выбора назад ведёт на выбор роли`() {
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.ABOUT, hasSelection = false))
    }

    @Test
    fun `на экране роли назад ничего не меняет`() {
        // BackHandler там выключен: там «Назад» = выход из приложения.
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.PICK_ROLE, hasSelection = true))
        assertEquals(Screen.PICK_ROLE, backTargetFor(Screen.PICK_ROLE, hasSelection = false))
    }

    @Test
    fun `из расписания назад ведёт на выбор сущности а не обратно в расписание`() {
        // Возврат с экрана расписания в SCHEDULE без смены выбора был бы
        // возвратом в никуда; с выбранной сущностью ведём на её выбор.
        assertEquals(Screen.PICK_ENTITY, backTargetFor(Screen.SCHEDULE, hasSelection = true))
    }

    @Test
    fun `ни один экран не ведёт назад сам в себя кроме выбора роли`() {
        // Проверка на «зацикливание»: BackHandler на экране роли выключен,
        // остальные экраны обязаны уводить в другой экран.
        for (screen in Screen.entries) {
            val target = backTargetFor(screen, hasSelection = true)
            if (screen == Screen.PICK_ROLE) {
                assertEquals(Screen.PICK_ROLE, target)
            } else {
                assertTrue("экран $screen ведёт сам в себя", target != screen)
            }
        }
    }

    @Test
    fun `ни один экран без выбора не ведёт на расписание`() {
        // Пустой выбор означает, что показывать нечего: SCHEDULE без
        // сущности и был причиной падения на `selection!!`.
        for (screen in Screen.entries) {
            val target = backTargetFor(screen, hasSelection = false)
            assertTrue(
                "экран $screen без выбора вёл на расписание",
                target != Screen.SCHEDULE,
            )
        }
    }

    // ── Шкала дней недели ───────────────────────────────────────────────

    @Test
    fun `все семь дней Calendar дают свою позицию без сдвига`() {
        // Calendar: SUNDAY=1, MONDAY=2 … SATURDAY=7. Наша шкала: 0=Пн…6=Вс.
        val expected = mapOf(1 to 6, 2 to 0, 3 to 1, 4 to 2, 5 to 3, 6 to 4, 7 to 5)
        for ((calendar, our) in expected) {
            assertEquals("день Calendar=$calendar", our, dayIndexFromCalendar(calendar))
        }
    }

    @Test
    fun `дни недели не повторяются и покрывают всю шкалу`() {
        val seen = (1..7).map { dayIndexFromCalendar(it) }
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), seen.toSet())
    }

    @Test
    fun `пятница это четвёртый день а не суббота`() {
        // Сдвиг на единицу называл пятницу «Сб» и подсвечивал чужие карточки.
        assertEquals(4, dayIndexFromCalendar(6))
        assertEquals("Пт", DAY_SHORT[dayIndexFromCalendar(6)])
    }

    // ── Роль: ключ и восстановление ─────────────────────────────────────

    @Test
    fun `роль восстанавливается по своему ключу`() {
        for (role in listOf(Role.STUDENT, Role.TEACHER, Role.AUDIENCE)) {
            assertEquals(role, Role.fromKey(role.key))
        }
    }

    @Test
    fun `неизвестный или пустой ключ роли даёт студента`() {
        // Ключ мог испортиться: старый формат prefs, ручная правка файла.
        assertEquals(Role.STUDENT, Role.fromKey(null))
        assertEquals(Role.STUDENT, Role.fromKey(""))
        assertEquals(Role.STUDENT, Role.fromKey("что-то"))
    }
}