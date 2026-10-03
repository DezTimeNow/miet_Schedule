package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Отчёт об ошибке: что уходит в письмо и чем приложение защищено.
 *
 * Требование пользователя: кнопка «Сообщить об ошибке» в разделе
 * «О программе» после кнопки обновления, отчёт приходит на почту без
 * бэкенда.
 *
 * Регрессии, которые закрывает этот файл.
 *
 * 1. Ключ Web3Forms попал в исходники как есть — это нормально, ключ
 *    публичный, но его нельзя потерять или переименовать молча: без него
 *    отчёт уходит в никуда и кнопка работает «вроде бы».
 *
 * 2. Honeypot `botcheck` не должен уходить от настоящего пользователя.
 *    В HTML ловушка работает потому, что браузер шлёт все поля формы.
 *    Приложение полей формы не имеет: если отправить `botcheck=""`,
 *    то это неотличимо от ботовой отправки, и настоящие отчёты будут
 *    отброшены вместе с мусором.
 *
 * 3. Персональные данные в письмо попадать не должны: выбранная группа
 *    и аудитория относятся к пользователю, а для разбора не нужны.
 */
class ReportTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    private fun sample(
        role: Role = Role.STUDENT,
        selection: String? = "ПИН-11",
        lessons: Int = 6,
        error: String? = null,
        comment: String = "",
    ) = ReportData(
        versionName = "0.39.0-alpha",
        versionCode = 39,
        role = role,
        selection = selection,
        lessonsShown = lessons,
        errorText = error,
        updateCheckedAt = 0L,
    )

    // ── кнопка и маршрут ──────────────────────────────────────────────────

    @Test
    fun `кнопка отчёта есть в разделе о программе`() {
        val s = src("AboutScreen.kt")
        assertTrue("В «О программе» нет кнопки отчёта", s.contains("\"Сообщить об ошибке\""))
        assertTrue("Кнопка ни к чему не ведёт", s.contains("onClick = onReport"))
        assertTrue("У экрана нет параметра onReport", s.contains("onReport: () -> Unit = {}"))
    }

    @Test
    fun `кнопка отчёта стоит сразу под проверкой обновлений`() {
        val s = src("AboutScreen.kt")
        val updateAt = s.indexOf("\"Проверить обновления\"")
        val reportAt = s.indexOf("\"Сообщить об ошибке\"")
        assertTrue("Не найдена кнопка проверки обновлений", updateAt >= 0)
        assertTrue("Не найдена кнопка отчёта", reportAt >= 0)
        assertTrue(
            "Кнопка отчёта должна идти после кнопки обновления",
            reportAt > updateAt,
        )
        // Между ними не должно быть блоков описания работы.
        val between = s.substring(updateAt, reportAt)
        assertFalse(
            "Между кнопками попал блок описания",
            between.contains("AboutBlock("),
        )
    }

    @Test
    fun `из отчёта назад ведёт в о программе`() {
        val s = src("MainActivity.kt")
        // Маршрут назад: назад — в «О программе» безусловно, а не через
        // backTargetFor, где REPORT уводил бы на экран расписания.
        assertTrue(
            "REPORT не возвращает в ABOUT",
            s.contains("Screen.REPORT -> Screen.ABOUT"),
        )
        assertTrue("REPORT нет в enum Screen", s.contains("ABOUT, REPORT }"))
    }

    // ── содержимое письма ──────────────────────────────────────────────────

    @Test
    fun `в письме есть версия роль и состояние данных`() {
        val body = sample().body("")
        assertTrue("Нет версии", body.contains("0.39.0-alpha"))
        assertTrue("Нет номера сборки", body.contains("39"))
        assertTrue("Нет роли", body.contains("Студент"))
        assertTrue("Нет числа пар", body.contains("6"))
        assertTrue("Нет состояния данных", body.contains("ошибок нет"))
    }

    @Test
    fun `текст ошибки с экрана попадает в письмо`() {
        val body = sample(lessons = 0, error = "Не удалось загрузить расписание").body("")
        assertTrue("Текст ошибки не попал в письмо", body.contains("Не удалось загрузить расписание"))
        assertTrue("Состояние не отмечено как ошибочное", body.contains("ошибка на экране"))
    }

    @Test
    fun `состояние экрана расписания доходит до отчёта`() {
        // Отчёт открывается из «О программе», когда расписание уже закрыто.
        // Без проброса состояния наружу письмо всегда содержало бы
        // «пар: 0, ошибок нет» — то есть ровно то, ради чего отчёт нужен.
        val main = src("MainActivity.kt")
        assertTrue(
            "Состояние расписания не пробрасывается наружу",
            main.contains("onScreenState = { n, err -> reportLessons = n; reportError = err }"),
        )
        assertTrue(
            "В отчёт идёт константа вместо реального числа пар",
            main.contains("lessonsShown = reportLessons"),
        )
        assertTrue(
            "В отчёт идёт константа вместо реального текста ошибки",
            main.contains("errorText = reportError"),
        )
        val ui = src("ScheduleUi.kt")
        assertTrue(
            "Экран расписания не сообщает своё состояние",
            ui.contains("onScreenState(lessons.size, error)"),
        )
    }

    @Test
    fun `комментарий пользователя попадает в письмо`() {
        val body = sample().body("Расписание не открывается")
        assertTrue("Комментарий потерялся", body.contains("Расписание не открывается"))
        assertTrue("Нет заголовка комментария", body.contains("Комментарий пользователя:"))
    }

    @Test
    fun `пустой комментарий не оставляет пустой раздел`() {
        // Без комментария в письме остаётся «Комментарий не указан.» —
        // так понятно, что человек ничего не писал, а не что отчёт
        // оборвался на середине.
        val body = sample().body("")
        assertTrue("Нет пояснения об отсутствии комментария", body.contains("Комментарий не указан"))
        assertFalse("Пустой комментарий дал пустой раздел", body.contains("Комментарий пользователя:"))
    }

    // ── приватность ───────────────────────────────────────────────────────

    @Test
    fun `выбранная группа и аудитория в письмо не попадают`() {
        val body = sample(selection = "ПИН-11").body("")
        assertFalse("Группа попала в письмо", body.contains("ПИН-11"))
        assertTrue("Нужно хотя бы указать, что выбор был", body.contains("выбрано"))

        val aud = sample(role = Role.AUDIENCE, selection = "1201 (м)").body("")
        assertFalse("Аудитория попала в письмо", aud.contains("1201"))
    }

    @Test
    fun `фио преподавателя в письмо попадает`() {
        // Расписание преподавателя собирается из чужих групп, и без ФИО
        // невозможно понять, чьё именно расписание сломалось.
        val body = sample(role = Role.TEACHER, selection = "Агамалиев Рустам Тельман оглы").body("")
        assertTrue("ФИО преподавателя потерялось", body.contains("Агамалиев"))
    }

    @Test
    fun `идентификаторы устройства в письмо не попадают`() {
        val body = sample().body("")
        assertFalse("ANDROID_ID попал в отчёт", body.contains("android_id"))
        assertFalse("IMEI попал в отчёт", body.toLowerCase().contains("imei"))
    }

    // ── отправка ──────────────────────────────────────────────────────────

    @Test
    fun `ключ и адрес сервиса на месте`() {
        val s = src("ReportSender.kt")
        assertTrue("Нет ключа формы", s.contains("25921919-b90b-47d9-822e-ba031dfca29c"))
        assertTrue("Нет адреса сервиса", s.contains("https://api.web3forms.com/submit"))
    }

    @Test
    fun `honeypot не отправляется от пользователя`() {
        val json = ReportSender.buildJson(sample(), "тест")
        // Ключевой момент: настоящий пользователь это поле НЕ шлёт.
        // Если отправлять botcheck="" — сервис не отличит живой отчёт от
        // ботового и отбросит оба.
        assertFalse(
            "botcheck отправляется — настоящие отчёты будут отброшены как спам",
            json.contains("botcheck"),
        )
        assertTrue("Нет ключа в теле запроса", json.contains("\"access_key\""))
        assertTrue("Нет тела письма", json.contains("\"message\""))
    }

    @Test
    fun `отправка идёт постом с json`() {
        val s = src("ReportSender.kt")
        assertTrue("Нет POST", s.contains(".post(payload.toRequestBody(JSON))"))
        assertTrue("Не задан тип содержимого", s.contains("application/json"))
    }

    @Test
    fun `частота отправок ограничена`() {
        val s = src("ReportSender.kt")
        assertTrue("Нет ограничения частоты", s.contains("const val RATE_LIMIT_MS"))
        // Проверяем, что ограничение проверяется ДО сетевого вызова.
        val left = s.indexOf("val left = leftRateLimitMs(app)")
        val net = s.indexOf("client.newCall(request)")
        assertTrue("Ограничение не найдено", left >= 0)
        assertTrue("Сетевой вызов не найден", net >= 0)
        assertTrue("Ограничение проверяется после запроса", left < net)
    }

    @Test
    fun `подпись ограничения читается по-человечески`() {
        assertEquals(
            "Можно отправить через минуту",
            ReportSender.rateLimitLabel(30_000L),
        )
        // Ровно 15 минут остатка — это «15 мин», а не «16»: округление
        // вверх не должно вылезать за пределы реального остатка.
        assertEquals(
            "Неверная подпись для ровно 15 минут",
            "Можно отправить через 15 мин",
            ReportSender.rateLimitLabel(15L * 60_000L),
        )
        assertEquals(
            "Неверная подпись для 14 минут 59 секунд",
            "Можно отправить через 15 мин",
            ReportSender.rateLimitLabel(14L * 60_000L + 59_000L),
        )
        assertEquals(
            "Округление не должно давать ноль минут",
            "Можно отправить через минуту",
            ReportSender.rateLimitLabel(1_000L),
        )
        assertEquals(
            "При нулевом остатке подпись не нужна",
            "Можно отправить через минуту",
            ReportSender.rateLimitLabel(0L),
        )
    }

    @Test
    fun `в интерфейсе нет предупреждений о том что пользователь не может изменить`() {
        // Правило БОССа: в UI не выносим ограничения, на которые у него нет
        // влияния. Пользователь не может «починить» сторонний сервис, значит
        // предупреждать его об этом рано — он всё равно ничего не сделает.
        val s = src("ReportScreen.kt")
        for (bad in listOf("не гарантируем", "может не дойти", "сервис может", "нет гарантий")) {
            assertFalse("В интерфейсе есть лишнее предупреждение: $bad", s.contains(bad))
        }
    }

    // ── находка через сам отчёт ───────────────────────────────────────────

    @Test
    fun `код аудитории ищется и в локальном списке тоже`() {
        // ЭТОТ ТЕСТ ПОЯВИЛСЯ ИЗ-ЗА РЕАЛЬНОГО ОТЧЁТА. Приложение отправило
        // письмо само, с текстом «Не удалось определить аудиторию» — баг
        // нашли спустя релиз, и только потому, что отчёт работает.
        //
        // Причина: список аудиторий из кэша расписаний собирался только на
        // экране выбора и нигде не сохранялся, а поиск кода смотрел лишь в
        // /audiences. Там 136 аудиторий из 194: корпус 8 (8102, 8109, 8307)
        // в эндпоинте не значится вовсе.
        val api = src("MietApi.kt")
        val lookup = api.substringAfter("fun audienceCodeByName")
            .substringBefore("\n    }")
        assertTrue(
            "Поиск кода аудитории не смотрит в локальный список",
            lookup.contains("localAudiences()"),
        )
        assertTrue(
            "Локальный список аудиторий не сохраняется",
            api.contains("fun saveLocalAudiences("),
        )
        val pickers = src("PickersScreen.kt")
        assertTrue(
            "Локальный список не сохраняется на экране выбора",
            pickers.contains("api.saveLocalAudiences(extra)"),
        )
    }
}
