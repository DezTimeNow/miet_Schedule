package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Структура главной страницы после 0.62.
 *
 * Требование владельца, дословно: «пользователь видит избранное, затем
 * кнопки выбрать роли и Избранное, затем идёт нижнее подменю», плюс
 * отдельные пункты: роли — в отдельный экран, в шапке нет подписи
 * «Кто ты?», «Меню» заменён домиком, кнопка «Избранное» одна.
 *
 * Проверки текстовые, а не запуском Activity: компонуемый проверить на
 * устройстве, а правило «что за экран и что на нём должно быть» — здесь.
 */
class HomeLayoutTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String =
        File(mainDir, name).readText()

    /** Комментарии содержат старый код как объяснение правки — их надо снять. */
    private fun code(name: String): String =
        src(name)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    // ── 1. Роли вынесены в отдельный экран ─────────────────────────────────

    /**
     * Три карточки ролей больше не занимают первый экран.
     *
     * Замер на устройстве до правки: карточки «Студент / Преподаватель /
     * Аудитория» занимали около 330 px при трёх избранных, и избранное
     * уходило за нижний край.
     */
    @Test
    fun `на главной нет карточек ролей`() {
        val home = code("HomeScreen.kt")
        // Функция карточки определена в этом же файле — рядом с экраном
        // выбора роли. Проверять надо ВЫЗОВ: на главной карточек быть не
        // должно, а объявление функции само по себе её не рисует.
        val homeScreen = home.substringBefore("fun RoleSelectScreen(")
        assertFalse(
            "Карточки ролей должны быть только на экране выбора роли",
            homeScreen.contains("RoleSelectCard("),
        )
        assertTrue(
            "Главная должна вести в экран выбора роли кнопкой",
            homeScreen.contains("onPickRole()"),
        )
    }

    @Test
    fun `экран выбора роли существует и содержит все три роли`() {
        val home = src("HomeScreen.kt")
        assertTrue("Нужен отдельный экран выбора роли", home.contains("fun RoleSelectScreen("))
        // Роли перечисляются через enum, а не тремя вызовами подряд: иначе
        // при добавлении четвёртой роли её забыли бы внести.
        assertTrue(
            "Список ролей должен перечисляться через Role.entries",
            Regex("""Role\.entries\.forEach""").containsMatchIn(home),
        )
    }

    // ── 2. Подпись «Кто ты?» убрана ────────────────────────────────────────

    @Test
    fun `в шапке главной нет подписи Кто ты`() {
        val main = code("MainActivity.kt")
        // Раньше главная отдавала subtitle = "Кто ты?". Подпись обещала, что
        // экран про выбор роли, а он про избранное — и роль на нём не
        // определяется, а выбирается кнопкой ниже.
        assertFalse(
            "Подпись «Кто ты?» должна быть убрана из главного меню",
            main.contains("\"Кто ты?\""),
        )
    }

    // ── 3. Домик вместо «Меню» ─────────────────────────────────────────────

    @Test
    fun `кнопка Меню заменена иконкой дома`() {
        val bar = code("MietTopBar.kt")
        assertFalse(
            "Текстовая кнопка «Меню» должна быть заменена иконкой дома",
            bar.contains("Text(\"Меню\""),
        )
        assertTrue("В шапке должна быть иконка дома", bar.contains("Icons.Filled.Home"))
    }

    /**
     * На самой главной слот дома пуст.
     *
     * Кнопка, ведущая в себя же, читается как сломанная. Но слот резервируется
     * обязательно — иначе заголовок на главной стоял бы на 48 px левее, чем на
     * всех остальных экранах.
     */
    @Test
    fun `на главной слот дома пуст но зарезервирован`() {
        val bar = code("MietTopBar.kt")
        assertTrue(
            "Пустой слот под домом должен резервироваться шириной BACK_SLOT",
            Regex("""homeSlotEmpty[\s\S]{0,200}?Spacer\(Modifier\.width\(BACK_SLOT\)\)""")
                .containsMatchIn(bar),
        )
        assertTrue(
            "Главная должна объявлять слот пустым",
            code("HomeScreen.kt").contains("homeSlotEmpty = true"),
        )
    }

    // ── 4. Порядок на экране ───────────────────────────────────────────────

    /**
     * Избранное → выбор роли → подменю. Порядок задан требованием.
     */
    @Test
    fun `порядок на главной избранное затем роль затем подменю`() {
        val home = code("HomeScreen.kt")
        val favIdx = home.indexOf("NextLessonCard(")
        val roleIdx = home.indexOf("onPickRole()")
        val menuIdx = home.indexOf("horizontalArrangement = Arrangement.Center")
        assertTrue("Не найден блок избранного", favIdx > 0)
        assertTrue("Не найдена кнопка выбора роли", roleIdx > 0)
        assertTrue("Не найдено служебное подменю", menuIdx > 0)
        assertTrue(
            "Порядок должен быть: избранное, выбор роли, подменю. " +
                "На деле: избранное=$favIdx, роль=$roleIdx, подменю=$menuIdx",
            favIdx < roleIdx && roleIdx < menuIdx,
        )
    }

    // ── 5. Подменю: три кнопки, «Избранное» не в нём ──────────────────────

    @Test
    fun `в подменю три кнопки и нет избранного`() {
        val home = code("HomeScreen.kt")
        val menu = home.substringAfter("horizontalArrangement = Arrangement.Center")
        val buttons = menu.substringBefore("}\n        }")
        assertTrue("Нет «Настройки»", buttons.contains("Text(\"Настройки\""))
        assertTrue("Нет «О программе»", buttons.contains("Text(\"О программе\""))
        assertTrue("Нет мини-игры", buttons.contains("Text(GAME_TAGLINE"))
        assertFalse(
            "«Избранное» в подменю быть не должно: вход один — из подписи блока",
            buttons.contains("Text(\"Избранное\""),
        )
    }

    /**
     * Цвет мини-игры — как у соседей.
     *
     * Владелец заметил сразу после переноса: серая подпись среди синих
     * выглядит как отключённая кнопка, хотя игра доступна всегда.
     */
    @Test
    fun `мини-игра нарисована тем же цветом что и соседние кнопки`() {
        val home = code("HomeScreen.kt")
        assertTrue(
            "Подпись мини-игры должна быть MIET_BLUE, а не приглушённой",
            Regex("""Text\(GAME_TAGLINE,\s*color = MIET_BLUE""").containsMatchIn(home),
        )
        assertFalse(
            "Приглушённый цвет для мини-игры больше не используется",
            Regex("""Text\(GAME_TAGLINE,\s*color = LocalAppColors""").containsMatchIn(home),
        )
    }

    // ── 6. Навигация ───────────────────────────────────────────────────────

    /**
     * Главная — точка входа, из неё возвращаться некуда.
     */
    @Test
    fun `с главной назад ведёт в себя а не в подменю`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.HOME, hasSelection = false))
        assertEquals(Screen.HOME, backTargetFor(Screen.HOME, hasSelection = true))
    }

    @Test
    fun `выбор роли это подменю главной`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = false))
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = true))
    }

    /**
     * Переход на выбор роли идёт через goTo, а не прямым присваиванием.
     *
     * Прямое `screen = ...` не записывает метку источника, и «Назад» ушёл бы
     * по правилу экрана вместо фактического входа. Такая ошибка уже была в
     * 0.60 со стрелкой «‹».
     */
    @Test
    fun `переход к выбору роли идёт через goTo`() {
        val main = code("MainActivity.kt")
        assertTrue(
            "Переход на выбор роли должен идти через goTo, а не напрямую",
            main.contains("goTo(Screen.PICK_ROLE)"),
        )
        assertFalse(
            "Прямое присваивание screen = Screen.PICK_ROLE обходит метку источника",
            main.contains("screen = Screen.PICK_ROLE"),
        )
    }

    /**
     * Домик ведёт на главную со всех подменю.
     */
    @Test
    fun `домик ведёт на главную`() {
        val main = code("MainActivity.kt")
        // Экран выбора роли — единственный, где домик обязателен: из него
        // «Назад» уводит на главную, и без домика возврат был бы только
        // системной кнопкой.
        assertTrue(
            "Экран выбора роли должен передавать возврат на главную",
            main.contains("onHome = { goTo(Screen.HOME) }"),
        )
    }
}
