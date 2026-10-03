package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Избранное: доступность кнопки и корректность открытия записи.
 *
 * Требование пользователя: «перемещаем из шапки в основное меню (ниже
 * выбора роли)» и «пользователь добавил группу, но если выберет её из
 * избранного выводит просто в выбор группы, короче криво-косо».
 *
 * Регрессии, которые закрывает этот файл.
 *
 * 1. Открытие из избранного вело в PICK_ENTITY: после тапа по группе
 *    пользователь попадал в список групп и должен был жать на неё ещё раз.
 *
 * 2. Для преподавателя не восстанавливался teacherCode. TeacherIndex ищет
 *    преподавателя по нормализованному ФИО (TeacherIndex.key), а из избранного
 *    приходит исходное имя, поэтому сравнение не проходило и экран показывал
 *    «Пар не найдено — проверь ФИО».
 *
 * 3. Для аудитории не восстанавливался внутренний код: расписание
 *    запрашивается по id из /audiences, а в избранном лежит только название.
 *    Без кода — ошибка «Не удалось определить аудиторию».
 *
 * 4. Выбор сохранялся через prefs.save(), который пишет под ключ ТЕКУЩЕЙ
 *    роли, а не той, по которой открывали: из вкладки «Преподаватели» при
 *    активной роли «студент» имя преподавателя попадало в слот студента.
 */
class FavoritesTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    // ── Доступность кнопки ────────────────────────────────────────────────

    @Test
    fun `избранное доступно в главном меню под выбором роли`() {
        val s = src("RolePickerScreen.kt")
        assertTrue(
            "В главном меню нет кнопки «Избранное»",
            s.contains("\"Избранное\""),
        )
        assertTrue(
            "Кнопка не вызывает onOpenFavorites",
            s.contains("onClick = { onOpenFavorites?.invoke() }"),
        )
    }

    @Test
    fun `кнопки главного меню стоят в одном ряду`() {
        val s = src("RolePickerScreen.kt")
        val row = s.substringAfter("horizontalArrangement = Arrangement.Center")
        val block = row.substringBefore("}\n        }\n    }")
        assertTrue("«Избранное» не в том же ряду, что «Настройки»",
            block.contains("\"Избранное\"") && block.contains("\"Настройки\""))
    }

    @Test
    fun `в шапке больше нет кнопки избранного`() {
        // Кнопка в шапке означала «любить текущую группу» рядом со звёздочкой
        // и на других экранах сдвигала соседей. Она переехала в главное меню.
        val s = src("MietTopBar.kt")
        assertTrue("В шапке остался вызов onOpenFavorites", !s.contains("onOpenFavorites"))
        assertTrue("В шапке осталась кнопка «Избранное»", !s.contains("\"Избранное\""))
    }

    @Test
    fun `запас шапки уменьшен под убранную кнопку`() {
        val s = src("MietTopBar.kt")
        // Раньше 208 dp: звезда + избранное + обновление + роль. Без избранного
        // 160 dp, иначе заголовок ужимается зря и выглядит обрезанным.
        assertTrue(
            "Ширина под кнопки не обновлена после убора избранного",
            s.contains("RESERVED_FOR_ACTIONS = 160"),
        )
    }

    // ── Открытие из избранного ─────────────────────────────────────────────

    @Test
    fun `из избранного открывается сразу расписание`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("onOpen = { r, value ->").substringBefore("\n            },")
        assertTrue(
            "Из избранного ведёт не в SCHEDULE, а в ${
                Regex("screen = (\\w+)").find(onOpen)?.groupValues?.get(1)
            }",
            onOpen.contains("screen = Screen.SCHEDULE"),
        )
        assertTrue(
            "Из избранного всё ещё ведёт в выбор сущности",
            !onOpen.contains("Screen.PICK_ENTITY"),
        )
    }

    @Test
    fun `преподавателю из избранного передаётся ключ ФИО`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("onOpen = { r, value ->").substringBefore("\n            },")
        // teacherCode для преподавателя — нормализованное ФИО, а не пустая
        // строка: с пустой lessonsOf ничего не находит.
        assertTrue(
            "Преподавателю не передаётся TeacherIndex.key(name)",
            onOpen.contains("TeacherIndex.key(value)"),
        )
    }

    @Test
    fun `аудитории из избранного находится внутренний код`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("onOpen = { r, value ->").substringBefore("\n            },")
        assertTrue(
            "Для аудитории не ищется код по названию",
            onOpen.contains("audienceCodeByName"),
        )
        assertTrue(
            "Для аудитории не сохраняется имя для фильтра кэша",
            onOpen.contains("roomNameArg = value"),
        )
    }

    @Test
    fun `выбор сохраняется под роль открытой вкладки`() {
        // prefs.save() берёт роль из хранилища, а не ту, по которой открыли.
        // Поэтому нужен saveFor(role, value).
        val prefs = src("GroupPrefs.kt")
        assertTrue("Нет GroupPrefs.saveFor(role, value)", prefs.contains("fun saveFor("))

        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("onOpen = { r, value ->").substringBefore("\n            },")
        assertTrue(
            "Выбор сохраняется через saveFor, а не save()",
            onOpen.contains("prefs.saveFor(r"),
        )
    }

    @Test
    fun `поиск кода аудитории добавлен в api`() {
        val s = src("MietApi.kt")
        assertTrue("Нет MietApi.audienceCodeByName", s.contains("fun audienceCodeByName("))
        // Сравнение по roomKey: имя в списке и в кэше может отличаться пробелами.
        assertTrue(
            "Поиск идёт по точному тексту, а не по roomKey",
            s.substringAfter("fun audienceCodeByName(").contains("roomKey(it.name) == want"),
        )
    }

    // ── Что именно хранится в избранном ────────────────────────────────────

    @Test
    fun `избранное преподавателя хранит имя а избранное аудитории название`() {
        val s = src("PickersScreen.kt")
        assertTrue("Преподаватель пишется в избранное не по имени", s.contains("toggleFavFor(Role.TEACHER, t.name)"))
        assertTrue("Аудитория пишется в избранное не по названию", s.contains("toggleFavFor(Role.AUDIENCE, n)"))
    }

    @Test
    fun `звезда в списке избранного означает удаление а не добавление`() {
        val s = src("FavoritesScreen.kt")
        assertTrue(
            "Звезда в избранном подписана как добавление",
            s.contains("\"Убрать из избранного\""),
        )
        assertTrue("Звезда не вызывает toggleFavFor", s.contains("prefs.toggleFavFor(current.first, value)"))
    }

    @Test
    fun `открытие избранного не сбрасывает выбор роли`() {
        // onOpen должен вызывать saveRole(r) ДО saveFor(r, ...): saveFor берёт
        // роль параметром, а save() — из хранилища. Проверяем, что порядок
        // в коде не нарушен, иначе после перезапуска откроется чужая роль.
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("onOpen = { r, value ->").substringBefore("\n            },")
        val saveRoleAt = onOpen.indexOf("prefs.saveRole(r)")
        val saveForAt = onOpen.indexOf("prefs.saveFor(r")
        assertTrue("Нет saveRole(r)", saveRoleAt >= 0)
        assertTrue("Нет saveFor(r, ...)", saveForAt >= 0)
        assertTrue(
            "saveRole должен идти раньше saveFor",
            saveRoleAt < saveForAt,
        )
    }

    @Test
    fun `все экраны с шапкой остались без параметра избранного`() {
        // Параметр onOpenFavorites больше не существует — если он где-то
        // остался, это либо ошибка компиляции, либо мёртвый код.
        val screens = listOf(
            "AboutScreen.kt", "FavoritesScreen.kt", "GroupPickerUi.kt",
            "PickersScreen.kt", "ScheduleUi.kt", "SettingsScreen.kt",
        )
        for (name in screens) {
            val s = src(name)
            assertTrue(
                "В $name остался onOpenFavorites — параметра больше нет",
                !s.contains("onOpenFavorites"),
            )
        }
    }

    @Test
    fun `переход назад из избранного ведёт туда же куда и стрелка`() {
        val s = src("MainActivity.kt")
        assertTrue(
            "Назад из избранного не через backTargetFor",
            s.contains("onBack = { screen = backTargetFor(Screen.FAVORITES, selection != null) }"),
        )
    }

    // ── Холодный старт ────────────────────────────────────────────────────

    @Test
    fun `при старте восстанавливается код аудитории`() {
        // Регрессия, найденная на 0.38 на эмуляторе: после force-stop
        // аудитория открывалась с «Не удалось определить аудиторию», хотя
        // через избранное работала. Причина: selection возвращается из
        // prefs, а teacherCode и roomNameArg инициализируются пустыми, и
        // ScheduleData делает teacherCode.toIntOrNull() -> null.
        val s = src("MainActivity.kt")
        val start = s.substringAfter("val startGroup = selection")
        val effect = start.substringBefore("\n    }")
        assertTrue(
            "Нет восстановления ключа роли при старте",
            effect.contains("LaunchedEffect(startGroup, role)"),
        )
        assertTrue(
            "Аудитория не восстанавливается: нет поиска кода по имени",
            effect.contains("api.audienceCodeByName(startGroup)"),
        )
        assertTrue(
            "Аудитория не восстанавливается: не проставляется имя комнаты",
            effect.contains("roomNameArg = startGroup"),
        )
        assertTrue(
            "Преподавателю не восстанавливается ключ ФИО",
            effect.contains("TeacherIndex.key(startGroup)"),
        )
    }

    @Test
    fun `восстановление при старте не мутирует состояние во время композиции`() {
        // teacherCode/roomNameArg — rememberSaveable. Их изменение прямо в
        // теле composable даёт нестабильное состояние и повторный вход в
        // эффекты, поэтому запись обязана быть в LaunchedEffect.
        val s = src("MainActivity.kt")
        val after = s.substringAfter("val startGroup = selection")
        assertTrue(
            "Состояние мутируется в теле composable, а не в эффекте",
            after.substringBefore("LaunchedEffect(startGroup, role)").split("teacherCode =").size == 1,
        )
    }

    @Test
    fun `студенту не достаётся код аудитории`() {
        // Проверка на смену роли: если роль — студент, ключи должны быть
        // пустыми, иначе расписание ищется по чужой комнате.
        val s = src("MainActivity.kt")
        val effect = s.substringAfter("LaunchedEffect(startGroup, role)")
            .substringBefore("\n        }\n    }")
        assertTrue("У студента ключи не очищаются", effect.contains("Role.STUDENT"))
    }

    @Test
    fun `аудитория из избранного ищется в обоих источниках`() {
        // Регрессия, найденная через отчёт об ошибке из приложения.
        // Локальный список аудиторий собирался только на экране выбора и
        // не сохранялся, поэтому при запуске и при открытии из избранного
        // поиск кода смотрел лишь в /audiences — там 136 аудиторий из 194,
        // и корпус 8 (8102, 8109, 8307) не находился вовсе.
        val api = src("MietApi.kt")
        assertTrue(
            "Нет чтения локального списка аудиторий",
            api.contains("fun localAudiences()"),
        )
        assertTrue(
            "Нет сохранения локального списка",
            api.contains("fun saveLocalAudiences("),
        )
        assertTrue(
            "Локальный список аудиторий нигде не сохраняется",
            src("PickersScreen.kt").contains("saveLocalAudiences"),
        )
    }
}
