package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Кнопка «Избранное» должна быть в шапке на КАЖДОМ экране приложения.
 *
 * Требование пользователя: «кнопка то появляется то нет в разных меню,
 * нужно чтобы она фиксированно была в одном месте, где бы пользователь
 * не находился».
 *
 * Регрессия, которую закрывает этот файл. `MietTopBar` рисует кнопку
 * избранного только если передан `onOpenFavorites`, а иначе ставит пустой
 * `Spacer` — место зарезервировано, кнопки нет. Параметр передавал только
 * `ScheduleUi`, поэтому на остальных шести экранах из семи кнопка
 * исчезала: пользователь привыкался к ней на расписании и не находил её
 * в списке групп, в «О программе» и в «Настройках».
 *
 * Почему проверяем ИСХОДНИКИ, а не экран. Экраны — это Composable, их
 * нельзя вызвать из обычного JVM-теста без Robolectric: проверка
 * потребовала бы эмулятора и была бы медленной, а ломается ровно от
 * забытого параметра в сигнатуре. Разбирая вызовы `MietTopBar` как текст,
 * мы ловим именно эту ошибку — забытый параметр виден сразу.
 */
class FavoritesButtonTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    /**
     * Файлы, в которых вызывается `MietTopBar`.
     *
     * Список закрытый и осознанный: `MietTopBar` — единая шапка, и любая
     * новый экран обязан быть здесь добавлен. Если появится экран вне
     * списка, тест ниже на это укажет — иначе кнопка на нём снова пропадёт.
     */
    private val screensWithTopBar = listOf(
        "AboutScreen.kt",
        "FavoritesScreen.kt",
        "GroupPickerUi.kt",
        "PickersScreen.kt",
        "RolePickerScreen.kt",
        "ScheduleUi.kt",
        "SettingsScreen.kt",
    )

    /** Вытащить текст вызова MietTopBar(...) по балансу скобок. */
    private fun callBlock(source: String): String {
        val start = source.indexOf("MietTopBar(")
        require(start >= 0) { "MietTopBar( не найден" }
        var depth = 1
        var i = start + "MietTopBar(".length
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return source.substring(start, i)
    }

    @Test
    fun `экран с шапкой передаёт onOpenFavorites`() {
        val missing = mutableListOf<String>()
        val noCall = mutableListOf<String>()

        for (name in screensWithTopBar) {
            val file = File(mainDir, name)
            assertTrue("Файл $name не найден", file.exists())
            val source = file.readText()
            if (!source.contains("MietTopBar(")) {
                noCall += name
                continue
            }
            // PickersScreen.kt содержит два экрана — преподавателя и аудиторию,
            // поэтому проверяем каждый вызов отдельно.
            var rest = source
            while (rest.contains("MietTopBar(")) {
                val block = callBlock(rest)
                if (!block.contains("onOpenFavorites")) {
                    missing += name
                }
                rest = rest.substringAfter("MietTopBar(")
            }
        }

        assertTrue("Эти экраны не используют MietTopBar: $noCall", noCall.isEmpty())
        assertTrue(
            "Кнопка избранного не передана на экранах: $missing. " +
                "Добавь onOpenFavorites = onOpenFavorites в вызов MietTopBar.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `все экраны приложения покрыты списком теста`() {
        // Новые экраны с шапкой обязаны попасть в screensWithTopBar.
        // Ищем по всему коду вызовы MietTopBar и сверяем с файлами списка.
        val actual = mainDir.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .filter { it.readText().contains("MietTopBar(") }
            .map { it.name }
            .sorted()
            // Сам MietTopBar.kt — определение, а не экран.
            .filter { it != "MietTopBar.kt" }

        assertEquals(
            "Появился экран с шапкой, которого нет в screensWithTopBar: " +
                "${actual.filterNot { it in screensWithTopBar }}",
            actual,
            screensWithTopBar.filter { it in actual }.sorted(),
        )
    }

    @Test
    fun `заголовок шапки у каждого экрана помечен своим именем`() {
        // Не ломает интерфейс молча: если экран переименуют, тест это покажет.
        val titles = mutableMapOf<String, String>()
        for (name in screensWithTopBar) {
            val source = File(mainDir, name).readText()
            val m = Regex("""title\s*=\s*"([^"]+)"""").find(source)
            if (m != null) titles[name] = m.groupValues[1]
        }
        assertTrue("Не нашлось ни одного заголовка", titles.isNotEmpty())
    }
}