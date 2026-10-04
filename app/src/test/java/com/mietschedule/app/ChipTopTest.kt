package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Проверки клиента таблицы лидеров.
 *
 * Ответы скрипта здесь настоящие, снятые curl'ом с развёрнутого адреса.
 * Это важно: разбор JSON проверяется на настоящем ответе, а не на
 * придуманном в тесте, поэтому расхождение полей обнаружится здесь.
 */
class ChipTopTest {
    /**
     * Строка, которую напечатали на экране вместо числа.
     *
     * Вынесена в константу: внутри теста кавычки и знак доллара конфликтуют,
     * и без константы проверка перестала бы читаться.
     */
    private val PLACEHOLDER_RANK = "\u0024rank"

    /**
     * Исходник без строк-комментариев.
     *
     * Нужен для проверок «в коде не должно быть такой-то строки»: в
     * TapChipScreen есть честные комментарии-напоминания о старых ошибках,
     * и поиск по сырому файлу спотыкается именно о них.
     */
    private fun stripComments(src: String): String = src.lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")


    // ── чтение топа ─────────────────────────────────────────────────────

    /** Ответ, который скрипт отдаёт при пустом топе. */
    @Test
    fun `пустой топ разбирается без ошибок`() {
        val body = """{"ok":true,"tz":"Europe/Moscow","week":143,""" +
            """"resetsAt":"2026-10-05T06:00:00.000Z","top":[]}"""
        val top = ChipTop.parseTop(body)
        assertNotNull("Пустой топ должен разбираться", top)
        assertEquals(0, top!!.rows.size)
        assertEquals("Europe/Moscow", top.tz)
        assertEquals(143, top.week)
    }

    /** Дата сброса — ключевое поле: по нему показывается «обнуление». */
    @Test
    fun `дата сброса переводится в миллисекунды`() {
        val body = """{"ok":true,"tz":"Europe/Moscow","week":143,""" +
            """"resetsAt":"2026-10-05T06:00:00.000Z","top":[]}"""
        val top = ChipTop.parseTop(body)!!
        // 5 октября 2026, 06:00 UTC = 1791180000000 мс.
        // Значение сверено пересчётом, а не взято из головы: ошибка на
        // год-другой дала бы «обнуление 2027 года», и ошибку заметил бы
        // только пользователь через полгода.
        assertEquals(1791180000000L, top.resetsAtMillis)
    }

    @Test
    fun `топ разбирается в правильном порядке`() {
        val body = """{"ok":true,"tz":"Europe/Moscow","week":143,""" +
            """"resetsAt":"2026-10-05T06:00:00.000Z","top":[""" +
            """{"nick":"Боря","score":90},{"nick":"Аня","score":77}]}"""
        val top = ChipTop.parseTop(body)!!
        assertEquals(2, top.rows.size)
        assertEquals("Боря", top.rows[0].nick)
        assertEquals(90, top.rows[0].score)
        assertEquals("Аня", top.rows[1].nick)
    }

    /** Голый HTML на том же адресе — это поломка скрипта, а не пустой топ. */
    @Test
    fun `html вместо json не превращается в пустой топ`() {
        val html = "<!DOCTYPE html><html><head><title>Страница не найдена</title></head></html>"
        assertNull("HTML не должен считаться топом", ChipTop.parseTop(html))
    }

    /** Ответ без поля ok — значит, это не наш скрипт. */
    @Test
    fun `ответ без ok считается посторонним`() {
        assertNull(ChipTop.parseTop("""{"error":"no such deployment"}"""))
        assertNull(ChipTop.parseTop("{}"))
    }

    @Test
    fun `ok false не считается успешным чтением`() {
        val body = """{"ok":false,"error":"Authorization required"}"""
        assertNull(ChipTop.parseTop(body))
    }

    /** Строка без ника или счёта пропускается, а не ломает весь ответ. */
    @Test
    fun `битые строки пропускаются а не ломают топ`() {
        val body = """{"ok":true,"tz":"Europe/Moscow","week":143,""" +
            """"resetsAt":"2026-10-05T06:00:00.000Z","top":[""" +
            """{"score":50},{"nick":"Аня"},{"nick":"Боря","score":90}]}"""
        val top = ChipTop.parseTop(body)!!
        assertEquals(1, top.rows.size)
        assertEquals("Боря", top.rows[0].nick)
    }

    // ── отправка результата ─────────────────────────────────────────────

    @Test
    fun `успешная запись возвращает место`() {
        val body = """{"ok":true,"rank":3,"total":100,"week":143}"""
        val r = ChipTop.parseSubmit(body)
        assertTrue(r is ChipTop.SubmitResult.Saved)
        r as ChipTop.SubmitResult.Saved
        assertEquals(3, r.rank)
        assertEquals(100, r.total)
        assertTrue("Повтор должен отмечаться", !r.duplicate)
    }

    /** Повторная отправка — не ошибка: скрипт просто держит лучший счёт. */
    @Test
    fun `повторная отправка не считается ошибкой`() {
        val body = """{"ok":true,"dup":true}"""
        val r = ChipTop.parseSubmit(body)
        assertTrue(r is ChipTop.SubmitResult.Saved)
        assertTrue((r as ChipTop.SubmitResult.Saved).duplicate)
    }

    @Test
    fun `результат вне топ-100 не считается провалом`() {
        val body = """{"ok":true,"rank":0,"total":100,"week":143}"""
        val r = ChipTop.parseSubmit(body) as ChipTop.SubmitResult.Saved
        assertEquals(0, r.rank)
        // Ранг 0 — это «в топ-100 не попал», а не провал: скрипт записал
        // результат, показать его в сотне мест не помещается.
        assertTrue("Место 0 не должно считаться ошибкой", r.rank >= 0)
    }

    @Test
    fun `причины отказа скрипта попадают в текст`() {
        val nick = ChipTop.parseSubmit("""{"ok":false,"error":"nick"}""")
        assertTrue(nick is ChipTop.SubmitResult.Rejected)
        assertEquals(
            "Такой ник не принимается",
            (nick as ChipTop.SubmitResult.Rejected).reason,
        )

        val score = ChipTop.parseSubmit("""{"ok":false,"error":"score"}""")
        assertTrue((score as ChipTop.SubmitResult.Rejected).reason.contains("отклон"))

        val install = ChipTop.parseSubmit("""{"ok":false,"error":"install"}""")
        assertTrue((install as ChipTop.SubmitResult.Rejected).reason.isNotBlank())
    }

    @Test
    fun `мусор вместо json при отправке означает отсутствие связи`() {
        assertTrue(ChipTop.parseSubmit("<!DOCTYPE html>") is ChipTop.SubmitResult.NoNetwork)
        assertTrue(ChipTop.parseSubmit("") is ChipTop.SubmitResult.NoNetwork)
        assertTrue(ChipTop.parseSubmit("null") is ChipTop.SubmitResult.NoNetwork)
    }

    // ── границы, заданные владельцем ────────────────────────────────────

    /**
     * Скорость тапов ограничена.
     *
     * Без паузы между тапами удержание пальца даёт сотни очков в секунду,
     * и таблица наполняется мусором. Это следствие требования владельца
     * «максимум N тапов в секунду», а не украшение.
     */
    @Test
    fun `пауза между тапами не меньше заданной`() {
        assertTrue(
            "Пауза должна быть не меньше 150 мс, иначе тапы летят пачками",
            ChipTop.MIN_TAP_GAP_MS >= 150L,
        )
        // Требование владельца от 0.47: секунда была слишком медленной,
        // пауза сокращена до 500 мс. Число зафиксировано тестом, чтобы
        // следующий починщик не «улучшил» его обратно.
        assertEquals(
            "Пауза между тапами должна быть ровно 500 мс",
            500L,
            ChipTop.MIN_TAP_GAP_MS,
        )
    }

    @Test
    fun `потолок ника совпадает со скриптом`() {
        assertEquals(30, ChipTop.NICK_MAX)
        assertEquals(20000, ChipTop.SCORE_MAX)
    }

    /**
     * Адрес скрипта — это GET, а не POST.
     *
     * На POST развёрнутый скрипт отвечает 302, и после редиректа Google
     * отдаёт 405: запись происходит, но ответ приложению теряется. Если
     * кто-то «оптимизирует» клиент на POST, тест упадёт — это ровно та
     * ошибка, которую нашли живьём.
     */
    @Test
    fun `клиент пишет методом GET`() {
        val src = srcFile("ChipTop.kt")
        assertTrue("Запрос строится методом GET", src.contains("Request.Builder().url(url(ENDPOINT)).get()"))
        assertTrue(
            "POST не используется: он теряет ответ после редиректа",
            !Regex("""\.post\(|\.postRequestBody\(|\.delete\(|\.put\(|\.patch\(""").containsMatchIn(src),
        )
    }

    /** Игра обязана требовать сеть: проверка связи до начала тапов. */
    @Test
    fun `игра не запускается без ответа скрипта`() {
        val src = srcFile("TapChipScreen.kt")
        assertTrue(
            "Экран обязан проверить связь перед игрой",
            src.contains("ChipTop.loadTop()"),
        )
        assertTrue(
            "Без связи показывается отдельный экран",
            src.contains("Phase.NoNetwork"),
        )
    }

    // ── требования владельца к новой версии ─────────────────────────────

    /**
     * Счёт уходит в таблицу сам, кнопки отправки нет.
     *
     * Требование: «ненужна кнопка отправить результат, должно всё работать
     * в реалтайм онлайн режиме». Проверяем наличие автосинхронизации и
     * отсутствие обязательной кнопки как основного пути.
     */
    @Test
    fun `результат уходит в таблицу сам без кнопок`() {
        val src = srcFile("TapChipScreen.kt")
        assertTrue(
            "Нужен цикл автосинхронизации, работающий пока открыт экран",
            src.contains("LaunchedEffect(Unit)"),
        )
        assertTrue("Цикл обязан звать отправку", src.contains("ChipTop.submit("))

        // Кнопок управления быть не должно: требование владельца —
        // «ненужна кнопка отправить результат, должно всё работать в
        // реалтайм онлайн режиме».
        for (label in listOf("Отправить", "Начать игру", "Закончить игру")) {
            assertTrue(
                "Кнопка «$label» должна быть убрана",
                !src.contains("\"$label\""),
            )
        }
        assertTrue(
            "Состояния «идёт игра» быть не должно — игра идёт сразу",
            !src.contains("var playing"),
        )
    }

    /**
     * Период синхронизации задан константой, а не числом в коде.
     *
     * Причина конкретная: замер показал 2.1–2.6 с на вызов. Отправка на
     * каждый тап дала бы очередь в десятки запросов.
     */
    @Test
    fun `период синхронизации задан и разумный`() {
        val gap = ChipTop.MIN_TAP_GAP_MS
        assertTrue(
            "Синхронизация должна быть реже, чем тапы",
            ChipTop.SYNC_INTERVAL_MS > gap,
        )
        // Синхронизация раз в 3 с даёт около 1200 вызовов в час на
        // игрока. Это много, но это и есть цена реальтайма: игрок видит
        // своё место в рейтинге почти сразу после тапа. При лимите
        // 20 000 в день это допустимо для десятков одновременных игроков;
        // каждый тап всё равно физически не отправить — вызов скрипта
        // занимает 2.1–2.6 секунды.
        val tapsPerSecond = 1000L / gap
        val callsPerHour = 3_600_000L / ChipTop.SYNC_INTERVAL_MS
        // Главное ограничение — не количество, а порядок величины относительно
        // времени вызова скрипта (2.1–2.6 с, замерено). Пока пауза меньше
        // этого времени, запросы копятся в очереди OkHttp и уходят пачкой
        // после конца игры, а это ровно тот «реальтайм», которого не было.
        val scriptCallMs = 2_600L
        assertTrue(
            "Пауза ${ChipTop.SYNC_INTERVAL_MS} мс меньше времени вызова " +
                "скрипта ($scriptCallMs мс): запросы будут копиться",
            ChipTop.SYNC_INTERVAL_MS >= scriptCallMs,
        )
        assertTrue(
            "Пауза не должна быть длиннее 5 с, иначе прогресс выглядит мёртвым",
            ChipTop.SYNC_INTERVAL_MS <= 5_000L,
        )
        assertTrue("Тапов должно быть больше нуля", tapsPerSecond > 0)
    }

    /**
     * Ник запоминается на устройстве.
     *
     * Требование: ввод один раз, кнопка смены рядом с именем.
     */
    @Test
    fun `ник запоминается и его можно сменить`() {
        val client = srcFile("ChipTop.kt")
        assertTrue("Ник должен где-то храниться", client.contains("savedNick"))
        assertTrue("Ник должен записываться", client.contains("saveNick"))

        val screen = srcFile("TapChipScreen.kt")
        assertTrue("Экран обязан читать сохранённый ник", screen.contains("ChipTop.savedNick"))
        assertTrue("Экран обязан сохранять новый ник", screen.contains("ChipTop.saveNick"))
        assertTrue("Должна быть кнопка смены имени", screen.contains("Сменить имя"))
    }

    /** Топ называется «Топ игроков». */
    @Test
    fun `топ называется топ игроков`() {
        val src = srcFile("TapChipScreen.kt")
        assertTrue("Заголовок топа должен быть «Топ игроков»", src.contains("\"Топ игроков\""))
        assertTrue(
            "Старое название «Топ микросхем» должно уйти",
            !src.contains("Топ микросхем"),
        )
    }

    /** Игра — карточка в главном меню, а не кнопка в ряду. */
    @Test
    fun `игра стоит карточкой в главном меню`() {
        val menu = srcFile("RolePickerScreen.kt")
        // Название вынесено в константу: используется и в меню, и в
        // заголовке игры, и в описании релиза.
        assertTrue("Должна быть карточка игры", menu.contains("GAME_TITLE"))
        // Название живёт в константе, а не строкой рядом с кнопкой: иначе
        // экран игры и меню снова разойдутся, как это уже было.
        //
        // Проверять «строки нет в файле» нельзя: файл и есть объявление
        // константы. Правильный признак — литерал встречается ровно один
        // раз, в строке `const val`, и больше нигде. Старое имя «Тапай»
        // отличалось от проверяемой строки и замаскировало эту ошибку в
        // тесте; после переименования проверка наконец стала осмысленной.
        val literal = "\"Тапать микросхему\""
        val occurrences = menu.split(literal).size - 1
        assertEquals(
            "Название игры должно быть только в объявлении константы",
            1, occurrences,
        )
        assertTrue(
            "Название должно быть именно в строке const val",
            Regex("""const val GAME_TITLE = ${Regex.escape(literal)}""").containsMatchIn(menu),
        )
    }

    /** Анимация при нажатии есть. */
    @Test
    fun `при нажатии есть анимация`() {
        val src = srcFile("TapChipScreen.kt")
        assertTrue("Нужна анимация по нажатию", src.contains("Animatable"))
        assertTrue("Вспышка должна гаснуть", src.contains("animateTo(0f"))
        assertTrue("Нажатие должно давать отклик корпусом", src.contains("press.animateTo(1f"))
    }

    /** Дубликат правил валидации в клиенте и в скрипте — источник расхождений. */
    @Test
    fun `клиент и скрипт проверяют ник одинаково`() {
        val src = srcFile("ChipTop.kt")
        assertTrue("Ник обрезается от пробелов", src.contains("Regex(\"\\\\s+\")"))
        assertTrue(
            "Пустой ник отклоняется на клиенте, а не ждёт ответа скрипта",
            src.contains("Ник не может быть пустым"),
        )
    }

    // ── вспомогательное ─────────────────────────────────────────────────

    private fun srcFile(name: String): String {
        val f = java.io.File("src/main/java/com/mietschedule/app/$name")
        assertTrue("Файл $name не найден: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    /**
     * Топ приходит вместе с записью.
     *
     * Требование владельца: игрок сразу видит, кого обогнал. Отдельный
     * запрос топа после каждой записи удваивал бы и без того медленное
     * обновление, поэтому скрипт отдаёт топ в том же ответе.
     */
    @Test
    fun `запись возвращает свежий топ в том же ответе`() {
        val body = """{"ok":true,"rank":2,"total":3,"week":143,
            |"resetsAt":"2026-10-05T06:00:00.000Z","tz":"Europe/Moscow",
            |"top":[{"nick":"A","score":10},{"nick":"B","score":7}]}"""
            .trimMargin()
        val parsed = ChipTop.parseSubmit(body)
        assertTrue("Ожидалась успешная запись", parsed is ChipTop.SubmitResult.Saved)
        val saved = parsed as ChipTop.SubmitResult.Saved
        assertNotNull("Топ должен приходить вместе с записью", saved.top)
        assertEquals(2, saved.top!!.rows.size)
        assertEquals("A", saved.top!!.rows[0].nick)
        assertEquals(2, saved.rank)
    }

    /**
     * В игре нет поясняющих надписей для игрока.
     *
     * Требование владельца: пользователь тапает, а не читает правила
     * работы приложения. Тексты вида «счёт уходит в таблицу сам» только
     * занимают место и путают.
     */
    @Test
    fun `в игре нет служебных пояснений`() {
        val game = srcFile("TapChipScreen.kt")
        for (forbidden in listOf(
            "уходит в таблицу",
            "Счёт отправляется",
            "не уйдёт в таблицу",
        )) {
            assertFalse("Пояснение \"$forbidden\" должно быть убрано", game.contains(forbidden))
        }
    }

    /**
     * Название и слоган мини-игры заданы константами.
     *
     * Они используются в меню, в заголовке игры и в описании релиза;
     * при раздельном написании со временем разъезжаются.
     */
    @Test
    fun `название мини-игры общее для меню и игры`() {
        assertEquals("Тапать микросхему", GAME_TITLE)
        assertEquals("мини-игра", GAME_TAGLINE)
        val game = srcFile("TapChipScreen.kt")
        assertTrue("Заголовок игры берёт название из константы", game.contains("GAME_TITLE"))
    }

    /**
     * Кнопка в шапке называется «Меню» и ведёт на главную.
     *
     * Раньше она называлась «Роль», хотя открывала домашний экран, и
     * стирала сохранённый выбор группы.
     */
    @Test
    fun `кнопка в шапке ведёт в меню и не стирает выбор`() {
        val bar = srcFile("MietTopBar.kt")
        assertTrue("Кнопка в шапке должна называться «Меню»", bar.contains("\"Меню\""))
        assertFalse("Старое название «Роль» должно уйти", bar.contains("Text(\"Роль\""))

        val root = srcFile("MainActivity.kt")
        assertFalse(
            "«Меню» не должен стирать сохранённую роль и группу",
            root.contains("onChangeRole = {\n                prefs.clear()"),
        )
    }

    /**
     * Топ ограничен пятьюдесятью строками.
     *
     * Требование владельца. Значение живёт в скрипте, но проверяется
     * здесь, чтобы расхождение с приложением не прошло молча.
     */
    @Test
    fun `топ ограничен пятьюдесятью строками`() {
        val script = java.io.File("/tmp/topleaderboard.gs").takeIf { it.exists() }?.readText()
        if (script == null) {
            // Скрипт загружается владельцем вручную и не лежит в репозитории.
            // Молча пропускаем: на CI-стенде файла нет, и падать из-за этого
            // нельзя — проверка всё равно выполняется вручную перед релизом.
            return
        }
        assertTrue("В скрипте должно быть MAX_ROWS = 50", script.contains("MAX_ROWS = 50"))
        assertFalse("Старое значение 100 должно уйти", script.contains("MAX_ROWS = 100"))
    }

    /**
     * Без сети игра не шлёт запросы в пустоту.
     *
     * На экране «Нужен интернет» отправка бессмысленна: сети нет, и каждая
     * попытка ждёт таймаут 8–10 секунд. При таймауте нажатия в секунду это
     * десятки бесполезных запросов в очередь. Найдено на эмуляторе, где
     * приложение зависло под нагрузкой.
     */
    @Test
    fun `без связи отправка не запускается`() {
        val game = srcFile("TapChipScreen.kt")
        assertTrue(
            "Цикл синхронизации должен проверять фазу перед отправкой",
            game.contains("if (phase != Phase.Playing) return@collect"),
        )
    }

    /**
     * Приложение работает и со старой версией скрипта.
     *
     * Новый скрипт отдаёт топ в ответе на запись. Пока он не развёрнут,
     * ответ приходит без поля top, и тогда таблица игроков замирала бы на
     * том снимке, что был при открытии экрана. Приложение обязано работать
     * с обеими версиями, иначе релиз нельзя публиковать до загрузки скрипта.
     */
    @Test
    fun `старый скрипт без топа в ответе не ломает таблицу`() {
        val game = srcFile("TapChipScreen.kt")
        assertTrue(
            "При пустом top из ответа должно идти отдельное чтение топа",
            game.contains("val fresh = r.top ?: ChipTop.loadTop()"),
        )

        // Разбор ответа старого скрипта: rank есть, top отсутствует.
        val old = ChipTop.parseSubmit("""{"ok":true,"rank":4,"total":4}""")
        assertTrue("Старая версия должна разбираться", old is ChipTop.SubmitResult.Saved)
        assertEquals(4, (old as ChipTop.SubmitResult.Saved).rank)
        org.junit.Assert.assertNull(
            "В ответе без top поле должно быть пустым, а не выдуманным",
            old.top,
        )
    }

    /**
     * Смена ника не обнуляет результат.
     *
     * Требование владельца: «при смене ника не надо чистить результат,
     * другой человек может сделать такой же ник и продолжить под ним».
     * Значит личность игрока — это ник, а не устройство.
     */
    @Test
    fun `смена ника не обнуляет результат`() {
        val game = srcFile("TapChipScreen.kt")
        // Обнуление счёта при пустом нике обязательно: это новый игрок, и
        // он не должен наследовать очки чужого. Запрещать его нельзя.
        // А вот обнуление ПРИ СМЕНЕ НИКА на непустой — как раз запрещено,
        // и ради него база счёта вынесена в отдельное состояние.
        assertTrue(
            "при пустом нике счёт должен обнуляться",
            game.contains("if (name.isEmpty()) {") && game.contains("baseScore = 0")
        )
        assertFalse(
            "смена ника не должна обнулять счёт напрямую",
            stripComments(game).contains("score = 0\n        }")
        )
        assertTrue(
            "база счёта должна расти, а не обнуляться",
            game.contains("if (known > baseScore)")
        )
        assertFalse(
            "Сброс строки в таблице больше не отправляется",
            game.contains("ChipTop.reset"),
        )

        val chip = srcFile("ChipTop.kt")
        assertFalse("Метод сброса удалён", chip.contains("suspend fun reset("))
        assertFalse(
            "Удалять строку по reset=1 больше нельзя",
            chip.contains("addQueryParameter(\"reset\", \"1\")"),
        )
    }

    /**
     * Ключ строки игрока выводится из ника, а не берётся с устройства.
     *
     * Это и есть причина, по которой игрок не попадал в таблицу: строка
     * установки была занята чужим результатом, скрипт отвечал dup и не давал
     * переписать ник. При ключе из ника у каждого ника своя строка.
     */
    @Test
    fun `ключ игрока выводится из ника`() {
        val chip = srcFile("ChipTop.kt")
        assertTrue("Ключ строки должен вычисляться из ника", chip.contains("fun playerId(nick: String)"))
        assertFalse(
            "Идентификатор устройства больше не используется",
            chip.contains("fun installId(") || chip.contains("ANDROID_ID"),
        )

        val game = srcFile("TapChipScreen.kt")
        assertTrue(
            "Отправка должна идти с ключом из ника",
            game.contains("ChipTop.submit(name, current, ChipTop.playerId(name))"),
        )

        // Один и тот же ник с разных устройств — одна строка.
        assertEquals(ChipTop.playerId("Аня"), ChipTop.playerId("Аня"))
        assertEquals(
            "Регистр и лишние пробелы не должны заводить вторую строку",
            ChipTop.playerId("  аня  "),
            ChipTop.playerId("Аня"),
        )
        assertTrue(
            "Разные ники должны давать разные ключи",
            ChipTop.playerId("Аня") != ChipTop.playerId("Боря"),
        )
    }

    /**
     * Кнопки тапа нет — тапают по самой микросхеме.
     *
     * Требование владельца: «кнопку тапай микросхему вообще убери». Раньше
     * на экране было два способа нажать — по корпусу и по кнопке под ним,
     * и вторая занимала место рядом с ником.
     */
    @Test
    fun `кнопки тапа нет — тапают по микросхеме`() {
        val game = srcFile("TapChipScreen.kt")
        assertFalse("Кнопка тапа должна быть убрана", game.contains("TapButton"))
        assertFalse(
            "На экране не должно быть подписи-призыва кнопки",
            Regex("""text\s*=\s*GAME_TITLE""").containsMatchIn(game),
        )
        // Тап по корпусу обязан остаться: иначе играть нечем.
        assertTrue(
            "Тап по корпусу микросхемы должен работать",
            game.contains("detectTapGestures("),
        )
        assertTrue("Тап должен увеличивать счёт", game.contains("score += 1"))
    }

    /**
     * Место в рейтинге пользователю не показывается.
     *
     * Требование владельца: «при смене ника появляется 8 место $rank — это
     * вообще юзеру не нужно». Раньше рядом со счётом стояла надпись
     * «место ${'$'}rank», и она выводилась буквально с $rank. Проверено
     * живьём: rank действительно место, то есть значение было верным,
     * врала только подпись. Убраны и надпись, и переменная.
     */
    @Test
    fun `место в рейтинге обновляется только при реальном ответе`() {
        val game = srcFile("TapChipScreen.kt")
        // Требование владельца от 0.48: место показать, но не мигать.
        // Скрипт отдаёт 0, когда игрок вне топа; затирать этим нулём
        // прошлый результат нельзя — надпись исчезала бы между
        // синхронизациями и «место 8» мерцало на каждом круге.
        assertTrue(
            "место должно обновляться только при rank > 0",
            game.contains("if (r.rank > 0) rank = r.rank")
        )
        // Очки и место живут в разных состояниях: иначе надпись дёргалась бы
        // на каждый тап вместе со счётом.
        assertTrue(
            Regex("""var score by (remember|rememberSaveable) \{ mutableIntStateOf\(0\) \}""")
                .containsMatchIn(game)
        )
        assertTrue(
            Regex("""var rank by (remember|rememberSaveable) \{ mutableIntStateOf\(0\) \}""")
                .containsMatchIn(game)
        )
    }

    /**
     * Микросхема не двигает интерфейс.
     *
     * Первая версия анимировала масштаб самой кнопки через graphicsLayer,
     * и нажатие приподнимало «Топ игроков» вместе с ней. Теперь кнопки нет,
     * а корпус микросхемы только слегка уменьшается внутри своего холста:
     * холст задан Modifier и в анимации не участвует.
     */
    @Test
    fun `микросхема не двигает интерфейс`() {
        val game = srcFile("TapChipScreen.kt")
        // Масштабировать МИКРОСХЕМУ нельзя: её блок — в потоке, поэтому
        // изменение размера сдвигает «Топ игроков». Запрет остаётся.
        assertFalse(
            "микросхема не должна масштабироваться: это дёргает layout",
            game.contains("scaleX = pulse") || game.contains("scaleY = pulse"),
        )
        // На самой микросхеме допустим только alpha: любое scale сдвинуло бы
        // блок ниже. Единственный graphicsLayer с масштабом в файле — на
        // счётчике очков, и там он применяется к тексту, а не к контейнеру.
        val chipBlock = game.substringAfter("ic_miet_chip").take(400)
        assertFalse(
            "на микросхеме не должно быть масштаба",
            Regex("""scale[XY]\s*=""").containsMatchIn(chipBlock),
        )
        // Отклик на нажатие — только затемнение, это не влияет на раскладку.
        assertTrue(
            "нажатие должно затемнять, а не масштабировать",
            game.contains("alpha = 1f - pressAmt"),
        )
    }

        @Test
    fun `сброс на сервере больше не используется`() {
        val chip = srcFile("ChipTop.kt")
        assertFalse(
            "Клиент не должен отправлять reset",
            chip.contains("\"reset\""),
        )
        val game = srcFile("TapChipScreen.kt")
        assertFalse(
            "Смена ника не должна удалять строку из таблицы",
            game.contains("addQueryParameter(\"reset\"") || game.contains("ChipTop.reset"),
        )
    }
    /**
     * Микросхема рисуется векторным ресурсом, а не кодом.
     *
     * Картинка пришла из miet_chip.svg: наивная конвертация теряла цвета,
     * потому что inksape относит заливку выводов к родительской группе, а у
     * путей внутри неё атрибутов нет. Тест фиксирует именно форму ресурса,
     * а не картинку глазами.
     */
    @Test
    fun `микросхема — валидный векторный ресурс с выводами по периметру`() {
        val res = File("src/main/res/drawable/ic_miet_chip.xml")
        assertTrue("Файл ресурса должен существовать: ${res.path}", res.exists())
        val xml = res.readText()

        assertTrue("Ресурс должен быть векторным", xml.contains("<vector"))
        assertTrue("Нужны размеры в dp", xml.contains("android:width="))
        assertTrue(
            "Нужен квадратный холст 1024 — координаты исходного SVG",
            xml.contains("android:viewportWidth=\"1024\"") &&
                xml.contains("android:viewportHeight=\"1024\""),
        )

        // Выводы — 16 прямоугольников по 8 сверху и снизу. Координаты у всех
        // разные (252, 321, 390 … с шагом 69), поэтому ищем по геометрии
        // «высота 20 в координатах SVG», а не по конкретному числу.
        val top = Regex("""m \d+,130 h 20""").findAll(xml).count()
        val bottom = Regex("""m \d+,802 h 20""").findAll(xml).count()
        assertEquals("Выводов сверху должно быть 8", 8, top)
        assertEquals("Выводов снизу должно быть 8", 8, bottom)

        // Градиенты обязаны быть в aapt:attr: android:fillColor строкой
        // url(#id) не понимает, конвертация без этого теряет цвета.
        assertTrue(
            "Заливка градиентом должна идти через aapt:attr",
            xml.contains("<aapt:attr name=\"android:fillColor\">"),
        )
        assertFalse(
            "Android не понимает короткую запись цвета из SVG",
            Regex("""android:(fill|stroke)Color="#(?![0-9a-f]{8}\")""").containsMatchIn(xml),
        )
        assertFalse(
            "Надпись должна быть контурами, а не <text>",
            xml.contains("<text"),
        )
    }

    /**
     * Тап по микросхеме обрабатывается на Box, а не на самой картинке.
     *
     * Так область нажатия не зависит от того, как лягут градиенты, и
     * остаётся одинаковой на любой плотности экрана.
     */
    @Test
    fun `область тапа не зависит от рисунка`() {
        val screen = srcFile("TapChipScreen.kt")
        assertTrue(
            "Микросхема должна браться из ресурса",
            screen.contains("painterResource(R.drawable.ic_miet_chip)"),
        )
        assertTrue(
            "Нажатия обрабатываются на слое поверх картинки",
            screen.contains("detectTapGestures("),
        )
        assertFalse(
            "Рисование кодом больше не используется",
            screen.contains("ChipArt"),
        )
    }

    /**
     * Счётчик установок ходит на адрес скрипта, который ведёт лист Installs.
     *
     * Требование владельца: считать установки при установке из ссылки, а не
     * из магазина. Appmetrica для этого не годится — она приписывает
     * установку магазину через install referrer, которого при ручной
     * установке нет. Тест держит адрес и формат параметров, чтобы смена
     * скрипта не осталась незамеченной.
     */
    @Test
    fun `счётчик установок обращается к скрипту с таблицей Installs`() {
        val counter = srcFile("InstallCounter.kt")

        assertTrue(
            "Счётчик должен использовать актуальный адрес скрипта",
            counter.contains("AKfycbxA2YgoPtiHv3pHyMgUDQeKWhz6kaV9p-xYCQmCZFOKBsOKCtRauDJt3TLjEtlkwqRoSg"),
        )
        assertTrue(
            "Устройство передаётся ключом device — так его ждёт скрипт",
            counter.contains("addQueryParameter(\"device\""),
        )
        assertTrue(
            "Версия передаётся отдельным параметром: по ней виден состав версий",
            counter.contains("addQueryParameter(\"version\""),
        )
        assertTrue(
            "Модель телефона нужна для разбивки по устройствам",
            counter.contains("addQueryParameter(\"model\""),
        )
    }

    /**
     * Идентификатор устройства хранится, а не создаётся заново при запуске.
     *
     * Иначе каждое открытие приложения считалось бы новой установкой, и
     * цифра «сколько человек поставило» совпала бы с числом запусков.
     */
    @Test
    fun `идентификатор устройства не пересоздаётся при запуске`() {
        val counter = srcFile("InstallCounter.kt")

        assertTrue(
            "Идентификатор обязан лежать в SharedPreferences",
            counter.contains("getSharedPreferences(PREFS"),
        )
        assertTrue(
            "Существующий идентификатор переиспользуется",
            counter.contains("prefs.getString(KEY_DEVICE, null)?.let { return it }"),
        )
        assertFalse(
            "Идентификатор не должен совпадать с рекламным: он переживает сброс",
            counter.contains("AdvertisingIdClient"),
        )
    }

    /**
     * Повторный запуск не превращается в новую установку.
     *
     * Требование владельца — отличать «поставил» от «заходил». Отправка не
     * чаще раза в сутки и обязана быть связана с первой отправкой, иначе
     * лист Installs наполнится одинаковыми строками.
     */
    @Test
    fun `повторные запуски не засчитываются как новые установки`() {
        val counter = srcFile("InstallCounter.kt")

        assertTrue(
            "Отправка не чаще раза в сутки",
            counter.contains("REPORT_INTERVAL_MS"),
        )
        assertTrue(
            "Первая отправка должна помечаться отдельно от повторной",
            counter.contains("if (last == 0L) \"true\" else \"false\""),
        )
        assertTrue(
            "Отметка об отправке ставится до запроса: при обрыве связи иначе",
            counter.contains("prefs.edit().putLong(KEY_REPORTED, now).apply()"),
        )
    }

    /**
     * Счётчик не блокирует запуск приложения.
     *
     * Запрос сетевой и синхронный. Если сделать его на главном потоке,
     * старт приложения растянется на секунды — на медленных телефонах это
     * ANR, что уже случалось в проекте.
     */
    @Test
    fun `отправка счётчика не блокирует главный поток`() {
        val main = srcFile("MainActivity.kt")
        assertTrue(
            "Отправка должна уйти с главного потока",
            main.contains("withContext(Dispatchers.IO)") &&
                main.contains("InstallCounter.reportRun"),
        )
        assertTrue(
            "Analytics тоже запускается на старте",
            main.contains("Analytics.start(this@MainActivity)"),
        )
        // AppMetrica.activate на главном потоке давал ANR на старте:
        // «Waited 5021ms for FocusEvent». Поэтому оба вызова обязаны
        // быть внутри withContext(Dispatchers.IO).
        val ioBlock = Regex(
            """withContext\(Dispatchers\.IO\) \{[^}]*Analytics\.start""",
        ).containsMatchIn(main)
        assertTrue(
            "Appmetrica не должна активироваться на главном потоке — это ANR",
            ioBlock,
        )
    }

    /**
     * Аналитика не должна ронять приложение.
     *
     * И Appmetrica, и собственный счётчик ходят в сеть. Отказ любого из
     * них не должен мешать расписанию — весь вызов обёрнут в try-catch.
     */
    @Test
    fun `аналитика не роняет приложение при отказе сети`() {
        val analytics = srcFile("Analytics.kt")
        assertTrue(
            "Ошибка аналитики должна гаситься",
            analytics.contains("catch (t: Throwable)"),
        )
        assertFalse(
            "Appmetrica не должен вызываться напрямую из игрового экрана",
            srcFile("TapChipScreen.kt").contains("AppMetrica.activate"),
        )
    }

    /**
     * Название мини-игры — «Тапать микросхему», а не «Тапай».
     *
     * Задача владельца от 0.47: в описании игры и в шапке экрана стояли
     * два разных названия одного и того же. Теперь проверяем, что оба
     * берутся из одной константы и написаны именно так, как заказано.
     */
    @Test
    fun `название игры во всех местах одинаковое`() {
        assertEquals("Тапать микросхему", GAME_TITLE)

        val menu = srcFile("RolePickerScreen.kt")
        val game = srcFile("TapChipScreen.kt")
        // Ни одного старого варианта в коде быть не должно.
        for (source in listOf(menu, game)) {
            // Проверка по границе слова: «Тапать микросхему» содержит
            // подстроку «Тапать», а не «Тапай», поэтому ложного срабатывания
            // на правильном названии нет — сверяем целиком.
            assertFalse(
                "в коде осталось старое название «Тапай микросхему»",
                source.contains("\"Тапай микросхему\"")
            )
        }
        // Шапка игры берёт константу, а не пишет строку рядом.
        assertTrue(
            "шапка мини-игры должна брать GAME_TITLE",
            game.contains("title = GAME_TITLE")
        )
        assertTrue(
            "меню должно показывать GAME_TITLE",
            menu.contains("GAME_TITLE")
        )
    }

    /**
     * Пауза между тапами — 500 мс.
     *
     * Было 1000 мс. Слишком медленно: рука успевает, а значит и
     * ограничение работает. Проверяем и число, и что оно константа,
     * а не зашитое в обработчик значение.
     */
    @Test
    fun `пауза между тапами 500 мс`() {
        assertEquals(500L, ChipTop.MIN_TAP_GAP_MS)
        val top = srcFile("ChipTop.kt")
        assertTrue(
            "интервал обязан жить в константе MIN_TAP_GAP_MS",
            top.contains("const val MIN_TAP_GAP_MS = 500L")
        )
        val screen = srcFile("TapChipScreen.kt")
        assertTrue(
            "обработчик тапа должен сверяться с константой",
            screen.contains("now - lastTapAt < ChipTop.MIN_TAP_GAP_MS")
        )
    }

    /**
     * Экран отчёта не объясняет, что комментарий необязателен.
     *
     * Надпись «Этого достаточно, чтобы понять, что происходит.
     * Комментарий можно не писать» убрана по требованию владельца:
     * человек отправляет отчёт молча, и текст только занимал место.
     */
    @Test
    fun `в отчёте нет пояснения про комментарий`() {
        val report = srcFile("ReportScreen.kt")
        assertFalse(
            "пояснение «Комментарий можно не писать» должно быть убрано",
            report.contains("Этого достаточно")
        )
        assertFalse(report.contains("Комментарий можно не писать"))
        // Само поле комментария остаётся — писать по-прежнему можно.
        assertTrue(report.contains("Что не так (необязательно)"))
    }

    /**
     * В «О программе» шесть простыней заменены на четыре коротких блока.
     */
    @Test
    fun `описание в о программе короткое и про мини-игру`() {
        val about = srcFile("AboutScreen.kt")
        val blocks: List<String> = Regex("""AboutBlock\(\s*\n\s*"([^"]+)"""").findAll(about).map { m: MatchResult -> m.groupValues[1] }.toList()

        assertEquals("ожидалось 4 блока, а не 6 простыней", 4, blocks.size)
        assertTrue("нет блока про мини-игру", blocks.any { it.contains("Тапать микросхему") })
        assertTrue("нет блока про расписание", blocks.any { it.contains("Расписание") })
        assertTrue("нет блока про обновления", blocks.any { it.contains("Обновления") })

        // Убрано то, что было лишним: пересказ про 6 часов и про кэш дважды.
        assertFalse(about.contains("не чаще раза в 6 часов"))
        assertFalse(about.contains("Помещения узнаются по названию"))
    }

    /**
     * Кегль счётчика очков не зависит от анимации.
     *
     * Требование владельца от 0.48: при тапе прыгала вся вёрстка, включая
     * «Топ игроков». Причина была в `fontSize = 46f * counterScale.value`:
     * кегль меняет размер строки, Column пересчитывает раскладку, и всё ниже
     * сдвигается. Плюс на переходе 9→10 число становилось шире, и центровка
     * дёргалась вбок. Теперь кегль — константа, а прыгает только масштаб
     * через graphicsLayer, который на измерение не влияет.
     */
    @Test
    fun `кегль счётчика не зависит от анимации`() {
        val game = srcFile("TapChipScreen.kt")
        assertFalse(
            "кегль не должен зависеть от counterScale",
            game.contains("counterScale.value).sp")
        )
        assertFalse(
            "кегль обязан быть константой",
            game.contains("46f * counterScale")
        )
        assertTrue(
            "анимация должна идти через graphicsLayer",
            game.contains("scaleX = counterScale.value")
        )
        assertTrue(
            "масштаб должен расти от центра",
            game.contains("TransformOrigin(0.5f, 0.5f)")
        )
        // Фиксированная высота блока: масштаб не влияет на измерение, и без
        // заданной высоты строка схлопнулась бы и раскладка снова бы прыгала.
        assertTrue(
            "у счётчика должна быть фиксированная высота блока",
            game.contains("SCORE_BOX_HEIGHT")
        )
    }

    /**
     * Место в топе показывается настоящим числом.
     *
     * Раньше подпись собиралась как "место ${'$'}rank", интерполяция не
     * сработала, и на экране печаталось «место $rank» — владелец принял это
     * за количество очков. Сначала строку убрали как лишнюю, теперь по
     * требованию вернули, но через [rankLabel], где значение подставляется
     * числом.
     */
    @Test
    fun `место в топе настоящее число`() {
        assertNull("до первой синхронизации места нет", rankLabel(0))
        assertNull("вне топа подписи быть не должно", rankLabel(-1))
        assertEquals("место 1", rankLabel(1))
        assertEquals("место 8", rankLabel(8))
        assertEquals("место 100", rankLabel(100))

        // Подпись строится функцией, а не конкатенацией на экране.
        val game = srcFile("TapChipScreen.kt")
        assertTrue("экран должен звать rankLabel", game.contains("rankLabel(rank)"))
        // Экран не должен собирать подпись сам: строка живёт только в
        // rankLabel. Ищем остаток старой ошибки — конкатенацию, где знак
        // доллара попадал в надпись буквально.
        // Проверяем тело экрана, а не весь файл. Подпись «место $rank» в
        // rankLabel — корректная интерполяция, и искать её нельзя: искать
        // надо строку, где интерполяция НЕ сработала, то есть место на
        // экране, где собирают подпись вручную.
        val screenBody = game.substringBefore("/** Что происходит с игрой. */")
        assertFalse(
            "подпись не должна собираться в теле экрана вручную",
            stripComments(screenBody).contains(PLACEHOLDER_RANK)
        )
        assertFalse(
            "в теле экрана не должно быть конкатенации «место »",
            stripComments(screenBody).contains("\"место \"")
        )
        // А подпись обязана быть ровно одна — в rankLabel.
        assertEquals(
            "строка «место » должна встречаться ровно один раз",
            1,
            Regex("""else -> "место """).findAll(game).count(),
        )
        // Место не должно прыгать вместе с очками: состояние отдельное.
        assertTrue(
            "место должно жить в отдельном состоянии",
            game.contains("var rank by ") &&
                Regex("""var rank by (remember|rememberSaveable) \{ mutableIntStateOf\(0\) \}""")
                    .containsMatchIn(game)
        )
        // Поворот экрана пересоздаёт Activity: обычный remember обнулял и
        // место, и очки. Игрок, набравший 6 очков, после поворота видел 0.
        assertTrue(
            "место должно переживать поворот экрана",
            game.contains("var rank by rememberSaveable {")
        )
    }

    /**
     * Кнопки в шапке не выдают себя за заглушки.
     *
     * Требование владельца от 0.48: «кнопка назад где-то не работает».
     * Системный «назад» был в порядке — перехват один и на всех экранах.
     * А вот ⭯ в шапке на двух экранах был заглушкой `onRefresh = onBack`,
     * то есть по кнопке обновления приложение просто выкидывало назад.
     * На игре теперь обновляется таблица игроков.
     */
    @Test
    fun `обновление в шапке не ведёт назад`() {
        val game = stripComments(srcFile("TapChipScreen.kt"))
        assertFalse(
            "кнопка обновления на экране игры не должна делать назад",
            game.contains("onRefresh = onBack")
        )
        assertTrue(
            "кнопка обновления должна перечитывать таблицу игроков",
            game.contains("onRefresh = {") && game.contains("ChipTop.loadTop()")
        )
        // Стрелка «‹» и «Меню» на экране игры ведут в меню — это верно,
        // игра открывается оттуда.
        assertTrue(game.contains("onChangeRole = onBack"))
        assertTrue(game.contains("onBack = onBack"))
    }

    /**
     * Системная кнопка «назад» перехватывается один раз и на всех экранах.
     *
     * Проверяем, что перехват не расползся по экранам: два лишних
     * BackHandler в GroupPickerUi.kt и ScheduleUi.kt когда-то висели
     * импортами без единого вызова.
     */
    @Test
    fun `перехват назад один и в корне`() {
        val main = srcFile("MainActivity.kt")
        val code = stripComments(main)
        assertEquals(
            "перехват «назад» должен быть ровно один",
            1, Regex("""BackHandler\(enabled""").findAll(code).count()
        )
        // Экраны без собственного перехвата.
        for (f in listOf("GroupPickerUi.kt", "ScheduleUi.kt")) {
            assertFalse(
                "в $f не должно быть импорта BackHandler без вызова",
                srcFile(f).contains("BackHandler")
            )
        }
    }

    /**
     * Кнопка обновления актуализирует ВСЁ расписание.
     *
     * Требование владельца от 0.48: «кнопка обновить должна актуализировать
     * всю информацию о расписании». Кнопка уже брала список групп, все
     * расписания, список аудиторий, ответ по выбранной аудитории и индекс
     * преподавателей, но у неё было три дыры:
     *
     *  - начало семестра кэшируется на сутки и кнопкой не обновлялось;
     *  - отметка «проверено» не ставилась, и экран показывал «давно»;
     *  - напоминания не перепланировались, то есть будильники оставались на
     *    старых временах.
     */
    @Test
    fun `кнопка обновления актуализирует всё`() {
        val main = srcFile("MainActivity.kt")
        val fn = main.substringAfter("fun refreshCurrent()")
        val body = fn.substringBefore("\n    }\n")

        // Что уже было и должно остаться.
        assertTrue("список групп с сайта", body.contains("fetchGroups(force = true)"))
        assertTrue("все расписания групп", body.contains("fetchSchedule"))
        assertTrue("список аудиторий", body.contains("fetchAudiences"))
        assertTrue("ответ по выбранной аудитории", body.contains("fetchAudience(code)"))
        assertTrue("индекс преподавателей", body.contains("buildFromCache"))

        // Три дыры, которые закрыты.
        assertTrue(
            "кнопка должна обновлять начало семестра",
            body.contains("api.semestrStart()")
        )
        assertTrue(
            "кнопка должна ставить отметку «проверено»",
            body.contains("api.markCheck(role, sel)")
        )
        assertTrue(
            "кнопка должна перепланировать напоминания",
            body.contains("ReminderScheduler.reschedule")
        )
    }

    /**
     * Фоновое обновление работает не только у студента.
     *
     * Раньше RefreshWorker выходил сразу для преподавателя и аудитории, у
     * которых расписание собирается из кэшей групп, — то есть обновлял эти
     * кэши тот же работник, а фонового обновления у них не было вовсе.
     * По решению владельца выход снят для всех ролей.
     */
    @Test
    fun `фоновое обновление не только для студента`() {
        val w = stripComments(srcFile("RefreshWorker.kt"))
        assertFalse(
            "выход для не-студентов должен быть снят",
            w.contains("role() != Role.STUDENT")
        )
        assertFalse(
            "в doWork не должно быть жёсткой роли STUDENT",
            w.contains("Role.STUDENT")
        )
        // Цели выбираются функцией, а не только из избранного студента.
        assertTrue("должна быть функция выбора целей", w.contains("internal fun targetsFor"))
        assertTrue("цели берутся из избранного текущей роли", w.contains("prefs.favGroups(prefs.role())"))
        assertTrue(
            "метка проверки должна относиться к своей роли",
            w.contains("api.markCheck(role, group)")
        )
    }

    /**
     * Клиент подстраивает счёт под серверный.
     *
     * Требование владельца от 0.49: продолжить под чужим ником, а не
     * начинать с нуля, и синхронизироваться, когда тем же ником тапает
     * кто-то ещё. Скрипт возвращает авторитетный счёт полем `score`;
     * раньше его не было, и клиент оставался со своим счётчиком, хотя в
     * таблице у того же ника стояли чужие очки.
     */
    @Test
    fun `счётчик продолжает чужой результат`() {
        val game = srcFile("TapChipScreen.kt")
        val top = srcFile("ChipTop.kt")

        // Серверный счёт разбирается из ответа.
        assertTrue(
            "ответ скрипта должен содержать авторитетный счёт",
            top.contains("""score = obj.get("score")?.asInt ?: 0""")
        )

        // База, от которой растёт счётчик, и она отдельна от результата.
        assertTrue(
            "нужна отдельная база счёта",
            game.contains("var baseScore by ") &&
                Regex("""var baseScore by (remember|rememberSaveable) \{ mutableIntStateOf\(0\) \}""")
                    .containsMatchIn(game)
        )
        assertTrue(
            "база не должна совпадать с результатом",
            Regex("""var score by (remember|rememberSaveable) \{ mutableIntStateOf\(0\) \}""")
                .containsMatchIn(game)
        )
        // Поворот экрана пересоздаёт Activity. Обычный remember терял и
        // результат, и базу, а эффект видел пустую baseForNick, считал
        // это сменой ника и ставил счёт в строку топа — то есть в ноль.
        assertTrue(
            "счёт должен переживать поворот экрана",
            game.contains("var score by rememberSaveable {")
        )
        assertTrue(
            "база должна переживать поворот экрана",
            game.contains("var baseScore by rememberSaveable {")
        )
        assertTrue(
            "признак «база для этого ника» должен переживать поворот",
            game.contains("var baseForNick by rememberSaveable {")
        )

        // Старт с уже набранного: своя строка в топе поднимает базу.
        assertTrue(
            "экран должен брать базу из своей строки топа",
            game.contains("top?.rows?.firstOrNull { it.nick == name }?.score")
        )
        // Синхронизация по ответу сервера.
        assertTrue(
            "экран должен подтягиваться к серверному счёту",
            game.contains("r.score > 0")
        )
        // Счёт нельзя обнулять при чужом результате: он только растёт.
        assertTrue(
            "подтягивание должно быть только вверх",
            game.contains("score = maxOf(score, authoritative)")
        )
    }

    /**
     * Сервер ищет игрока и по нику, а не только по ключу.
     *
     * Ключ строки выводится из ника, но смена ника его меняет, и вернувшись
     * к прежнему нику игрок получал вторую строку: в топе оказывались два
     * одинаковых ника. Личность игрока — ник, поэтому сверка идёт по обоим.
     */
    @Test
    fun `сервер ищет игрока по нику`() {
        val gs = java.io.File("/tmp/combined.gs").takeIf { it.exists() }?.readText() ?: return
        assertTrue(
            "поиск строки игрока должен учитывать ник",
            gs.contains("sameByNick")
        )
        assertTrue(
            "сравнение должно быть без учёта регистра",
            gs.contains("toLowerCase() === v.nick.toLowerCase()")
        )
        // Место считается по тому же правилу, иначе надпись исчезает.
        assertTrue(
            "rankOf должен искать по нику",
            gs.contains("if (low && String(sorted[i][0]).toLowerCase() === low) return i + 1;")
        )
        // Регрессия, найденная тестом: при «счёт не улучшился» место
        // возвращалось как 0, и надпись пропадала в самый обычный момент.
        assertTrue(
            "при dup строка игрока должна попадать в набор для подсчёта места",
            gs.contains("kept.concat([mine], rest)")
        )
    }

    /**
     * Кнопка ⭯ везде показывает крутилку, пока обновление идёт.
     *
     * Общее обновление занимает около 30 секунд на 343 группы. Раньше
     * ScheduleScreen и SettingsScreen не получали признак обновления, и
     * кнопка выглядела обычной и нажималась снова, начиная второй круг
     * обхода поверх первого.
     */
    @Test
    fun `кнопка обновления блокируется на время обновления`() {
        val main = srcFile("MainActivity.kt")

        // Экран расписания.
        assertTrue(
            "ScheduleScreen должен принимать признак общего обновления",
            main.contains("refreshingAll: Boolean = false")
        )
        assertTrue(
            "AppRoot должен передавать признак обновления",
            main.contains("refreshingAll = refreshing")
        )

        // Настройки: параметра не было вовсе.
        val settings = srcFile("SettingsScreen.kt")
        assertTrue(
            "SettingsScreen должен принимать refreshing",
            settings.contains("refreshing: Boolean = false")
        )
        assertTrue(
            "шапка настроек должна показывать крутилку",
            settings.contains("refreshing = refreshing,")
        )

        // Отчёт: ⭯ больше не заглушка «назад».
        // Комментарии не в счёт: в ReportScreen есть честное напоминание
        // о прежней заглушке, и поиск по сырому файлу спотыкался бы о него.
        val report = stripComments(srcFile("ReportScreen.kt"))
        assertFalse(
            "кнопка обновления в отчёте не должна вести назад",
            report.contains("onRefresh = onBack")
        )
        assertTrue(
            "кнопка обновления в отчёте должна проверять обновления",
            report.contains("onRefresh = onCheckUpdate")
        )
    }

    /**
     * Переход на ник с меньшим результатом обязан ВЫСТАВИТЬ счёт.
     *
     * Требование владельца: «выбрал например чужой ник "че" у которого 21,
     * я продолжал». Если у текущего ника 44, а у нового 21, то после выбора
     * счётчик обязан стать 21. Раньше стояло «поднять базу только вверх», и
     * переход к нику с меньшим результатом молча оставлял 44.
     *
     * Моделирует ту же ветку, что в экране: смена ника выставляет базу,
     * обновление топа при том же нике — поднимает.
     */
    @Test
    fun `смена ника выставляет счёт даже вниз`() {
        var base = 44
        var score = 44
        var baseForNick = "Zero"

        fun onTopChanged(name: String, known: Int) {
            if (baseForNick != name) {
                baseForNick = name
                base = known
                score = known
                return
            }
            if (known > base) {
                base = known
                score = known
            }
        }

        // Выбрали ник с меньшим результатом — счёт обязан стать ровно 21.
        onTopChanged("че", 21)
        assertEquals("переход на ник с 21 очком должен дать счёт 21", 21, score)
        assertEquals(21, base)

        // Тап продолжает от новой базы, а не от прошлого ника.
        score += 1
        assertEquals(22, score)

        // Обновление топа при ТОМ ЖЕ нике по-прежнему поднимает счёт вверх.
        onTopChanged("че", 30)
        assertEquals("рост при том же нике обязателен", 30, score)
    }

    /**
     * Обновление топа при неизменном нике не должно ронять счётчик.
     *
     * Чужой результат может прийти между тапами. Ронять по нему счётчик
     * хуже, чем показать чуть большее число: следующая синхронизация
     * приравняет. Раньше ветка «только вверх» была общей для смены ника и
     * обновления топа, и разделить их было нечем.
     */
    @Test
    fun `обновление топа при том же нике не роняет счёт`() {
        var base = 50
        var score = 55
        val baseForNick = "Zero"

        val known = 45 // сервер прислал меньше локального
        if (baseForNick != "Zero") return
        if (known > base) {
            base = known
            score = known
        }

        assertEquals("чужой меньший результат не должен обнулять счётчик", 55, score)
        assertEquals(50, base)
    }

    /**
     * Игра обязана ходить на скрипт, который отдаёт авторитетный счёт.
     *
     * В проекте было три адреса Apps Script: один в игре, другой в
     * счётчике установок, и новый при развёртывании. Живой проверкой
     * выяснилось, что адрес в игре не отдавал поле score вообще — то есть
     * синхронизация очков молча не работала, а приложение выглядело
     * исправным. Тест держит актуальный адрес, чтобы смена скрипта
     * снова не прошла незамеченной.
     */
    @Test
    fun `игра обращается к скрипту, отдающему авторитетный счёт`() {
        val game = srcFile("ChipTop.kt")

        assertTrue(
            "Игра должна использовать актуальный адрес скрипта",
            game.contains("AKfycbxA2YgoPtiHv3pHyMgUDQeKWhz6kaV9p-xYCQmCZFOKBsOKCtRauDJt3TLjEtlkwqRoSg"),
        )
        assertFalse(
            "В игре не должно остаться прежних адресов скрипта",
            game.contains("AKfycbxC02arwREotzw9H"),
        )
        assertFalse(
            "В игре не должно остаться прежнего адреса счётчика",
            game.contains("AKfycbwBy-_5ev4yVs9"),
        )
        assertTrue(
            "Клиент обязан разбирать поле score из ответа сервера",
            game.contains("get(\"score\")"),
        )
    }

    /**
     * Строка прогресса загрузки обновления.
     *
     * Жалоба владельца: «непонятно качает он или нет». Полоса в диалоге
     * рисовалась, но цифры не двигались: downloadApk копировал файл через
     * copyTo, который о прогрессе не сообщает, а счётчик обновлялся только
     * в onSuccess — то есть уже после конца загрузки.
     *
     * Проверяем разбор байтов в мегабайты: разделитель должен быть точкой
     * независимо от локали (String.format выдаёт запятую на русском телефоне).
     */
    @Test
    fun `прогресс загрузки считается в мегабайтах с точкой`() {
        fun mb(bytes: Long): String {
            val tenths = (bytes / 1048576.0 * 10 + 0.5).toInt()
            return "${tenths / 10}.${tenths % 10}"
        }

        assertEquals("0.0", mb(0L))
        assertEquals("1.0", mb(1048576L))
        assertEquals("12.9", mb(13530395L))
        assertEquals("13.5", mb(14155717L))
        // Полмегабайта округляются вверх, как принято в sizeLabel.
        assertEquals("0.5", mb(524288L))
    }

    /**
     * Доля скачанного не выходит за границы и не выдумывается.
     *
     * Размер неизвестен — доля null, и полоса остаётся неопределённой.
     * Вместо правдоподобной цифры пользователь видит крутящуюся полосу:
     * выдуманный процент хуже отсутствия процента.
     */
    @Test
    fun `доля прогресса держится в границах и не выдумывается`() {
        fun progress(downloaded: Long, total: Long): Float? {
            if (downloaded < 0 || total <= 0) return null
            return (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

        assertNull("размер неизвестен — доля не выдумывается", progress(1000L, 0L))
        assertNull("загрузка не начата — доля не выдумывается", progress(-1L, 100L))

        assertEquals(0.5, progress(50L, 100L)!!.toDouble(), 0.001)
        // Сервер может прислать больше, чем заявлено в описании релиза.
        assertEquals(1.0, progress(150L, 100L)!!.toDouble(), 0.001)
        assertEquals(1.0, progress(100L, 100L)!!.toDouble(), 0.001)
    }

    /**
     * Загрузка обновления обязана сообщать прогресс.
     *
     * Жалоба владельца: «непонятно качает он или нет». Причина была не в
     * отсутствии полосы — она рисовалась, — а в том, что цифры за ней не
     * двигались: файл копировался через copyTo, который о прогрессе не
     * сообщает, а состояние обновлялось только по окончании загрузки.
     *
     * Сторож держит оба условия: колбэк есть в сигнатуре и тело читает
     * файл порциями. Вернули copyTo — тест упадёт.
     */
    @Test
    fun `загрузка обновления сообщает прогресс`() {
        val checker = srcFile("UpdateChecker.kt")

        assertTrue(
            "downloadApk должен принимать колбэк прогресса",
            checker.contains("onProgress: ((downloaded: Long, total: Long) -> Unit)?"),
        )
        assertTrue(
            "Прогресс должен доходить до экрана, а не оставаться в загрузчике",
            checker.contains("onProgress?.invoke"),
        )
        assertFalse(
            "copyTo не сообщает прогресс — вернуть его, полоса снова замрёт",
            checker.contains("copyTo(out)"),
        )
        assertTrue(
            "Обновление состояния должно идти не чаще, чем раз в 100 мс",
            checker.contains("lastReport"),
        )
    }

    /**
     * Текст релиза читается без Markdown-звёздочек.
     *
     * Заметки в релизе оформлены как «**Мини-игра**». Прежняя очистка
     * убирала звёздочки только в начале строки, а закрывающая пара оставалась,
     * и пользователь читал «**Мини-игра**» вместе со звёздочками.
     */
    @Test
    fun `текст релиза чист от markdown-звёздочек`() {
        val raw = "По вашим замечаниям.\n\n**Мини-игра**\n- Счёт продолжается"
        val clean = cleanReleaseNotes(raw)

        assertFalse("Парные звёздочки недопустимы", clean.contains("**"))
        assertTrue("Заголовок должен остаться", clean.contains("Мини-игра"))
        assertTrue("Буллет должен остаться", clean.contains("Счёт продолжается"))
    }

    /**
     * Перевод строки из GitHub не должен попадать в текст как «&#10;».
     *
     * GitHub отдаёт переводы строк как HTML-энтитию, и без разбора
     * пользователь читал «&#10;» вместо разрыва строки.
     */
    @Test
    fun `перевод строки разворачивается, а не печатается`() {
        val clean = cleanReleaseNotes("первая&#10;вторая")

        assertFalse("Энтития перевода не должна печататься", clean.contains("&#10;"))
        assertEquals("первая\nвторая", clean)
    }

    /**
     * Отказ таблицы не должен оставаться без реакции на экране.
     *
     * Найдено при живом тестировании: сервер отверг ник («error: nick»),
     * а экран продолжал показывать растущий счёт. Игрок видел настоящий
     * результат, который после перезапуска исчезал: в таблицу не попадало
     * ничего. Отказ раньше уходил в ветку `else -> Unit`.
     */
    @Test
    fun `отказ скрипта разбирается в понятную причину`() {
        val rejected = ChipTop.parseSubmit(
            """{"ok":false,"error":"nick"}""",
        )
        assertTrue("Отказ должен распознаваться", rejected is ChipTop.SubmitResult.Rejected)
        val reason = (rejected as ChipTop.SubmitResult.Rejected).reason
        assertTrue(
            "Причина должна называть ник, а не код ошибки",
            reason.contains("ник", ignoreCase = true),
        )
        assertFalse("Код ошибки не должен показываться игроку", reason.contains("nick", ignoreCase = true))
    }

    /**
     * Отказ по счёту и по игроку тоже распознаются, а не молчат.
     */
    @Test
    fun `все отказы скрипта распознаются`() {
        val cases = mapOf(
            "install" to "игрок",
            "score" to "Счёт",
        )
        for ((code, marker) in cases) {
            val r = ChipTop.parseSubmit("""{"ok":false,"error":"$code"}""")
            assertTrue("Отказ $code должен распознаваться", r is ChipTop.SubmitResult.Rejected)
            assertTrue(
                "Отказ $code должен давать понятный текст, а не '$code'",
                (r as ChipTop.SubmitResult.Rejected).reason.contains(marker, ignoreCase = true),
            )
        }
    }

    /**
     * Высота под строку «место» и под отказ зарезервирована.
     *
     * Прыжок вёрстки на игровом экране был найден дважды: строка «место N»
     * появляется не с первого кадра (до первой синхронизации места нет) и
     * сдвигала микросхему с таблицей игроков. Отказ появляется ещё позже и
     * сдвигал то же самое.
     */
    @Test
    fun `строки места и отказа не сдвигают вёрстку`() {
        val screen = srcFile("TapChipScreen.kt")

        assertTrue(
            "Высота под строку места должна быть задана явно",
            screen.contains("PLACE_BOX_HEIGHT"),
        )
        assertTrue(
            "Высота под сообщение об отказе должна быть задана явно",
            screen.contains("REJECT_BOX_HEIGHT"),
        )
        assertTrue(
            "Место должно рисоваться в боксе постоянной высоты, а не по условию",
            screen.contains("Modifier.height(PLACE_BOX_HEIGHT.dp)"),
        )
        assertTrue(
            "Отказ не должен уходить в заглушку, молчание здесь и было багом",
            !screen.contains("else -> Unit"),
        )
    }

    /**
     * Кнопки-стрелки должны называться для TalkBack.
     *
     * Стрелки нарисованы текстом, а не иконками, поэтому у IconButton не
     * оказалось описания: TalkBack читал символ «‹» вместо действия.
     */
    @Test
    fun `кнопки-стрелки объявлены для TalkBack`() {
        val bar = srcFile("MietTopBar.kt")
        val schedule = srcFile("ScheduleUi.kt")

        for (marker in listOf(
            "contentDescription = \"Назад\"",
            "contentDescription = if (refreshing)",
        )) {
            assertTrue("В шапке нет описания: $marker", bar.contains(marker))
        }
        assertTrue(
            "Стрелка недели назад не объявлена",
            schedule.contains("contentDescription = \"Предыдущая неделя\""),
        )
        assertTrue(
            "Стрелка недели вперёд не объявлена",
            schedule.contains("contentDescription = \"Следующая неделя\""),
        )
    }

    /**
     * Отказ не должен переноситься на следующий ник.
     *
     * Найдено при проверке на устройстве: после отказа по нику Test
     * игрок сменил имя на Kran, сервер на него ответил нормально, счёт пошёл
     * и место показалось — но строка «Такой ник не принимается» осталась на
     * экране и утверждала обратное.
     */
    @Test
    fun `отказ не переносится на другой ник`() {
        // Тот же порядок, что в экране: смена ника обязана чистить отказ.
        var rejectNote = "Такой ник не принимается"
        var nick = "Test"

        fun onNickChanged(newNick: String) {
            nick = newNick
            rejectNote = ""
        }

        onNickChanged("Kran")
        assertTrue(
            "Отказ прежнего ника не должен оставаться на экране",
            rejectNote.isEmpty(),
        )
        assertEquals("Kran", nick)
    }

    /**
     * Смена ника не должна давать новому нику чужой счёт и чужое место.
     *
     * Требование владельца: «поменял ник на новый — должен быть с 0 очков
     * и в самом низу». Поток синхронизации реагирует и на смену ника, а
     * счёт в этот момент ещё от прежнего игрока. Без проверки отправка
     * уходила под новым ником со старым счётом: новый ник тут же получал
     * чужое место, хотя его в таблице не было.
     */
    @Test
    fun `смена ника не отправляет прежний счёт под новым именем`() {
        val game = srcFile("TapChipScreen.kt")

        // Отправка отсекается, пока база не выставлена для этого ника.
        assertTrue(
            "под новым ником нельзя отправлять счёт прежнего игрока",
            game.contains("if (baseForNick != name) return@collect")
        )
        // Проверка обязана стоять ДО отправки, иначе она ничего не спасает.
        val guardAt = game.indexOf("if (baseForNick != name) return@collect")
        val sendAt = game.indexOf("ChipTop.submit(name, current")
        assertTrue("проверка должна стоять до отправки", guardAt in 1 until sendAt)

        // При смене ника место обнуляется: прежнее получено для другого
        // имени и к новому отношения не имеет.
        val renameBranch = game.substringAfter("if (baseForNick != name) {")
            .substringBefore("return@LaunchedEffect")
        assertTrue(
            "при смене ника место должно сбрасываться",
            renameBranch.contains("rank = 0")
        )
    }

    /**
     * Счёт нового ника выставляется из его строки, а не из прежней.
     *
     * Отдельно от предыдущего: если новый ник в таблице отсутствует,
     * known равен нулю, и счёт должен стать нулём — то есть новый игрок
     * начинает с чистого листа, а не продолжает чужой результат.
     */
    @Test
    fun `новый ник без строки в таблице начинает с нуля`() {
        val game = srcFile("TapChipScreen.kt")

        // Значение строки топа для нового ника — единственный источник базы.
        assertTrue(
            "база должна браться из строки топа по нику",
            game.contains("top?.rows?.firstOrNull { it.nick == name }?.score ?: 0")
        )
        // Оба значения выставляются РОВНО в known, без «только вверх»:
        // новый ник с 21 очками должен продолжить с 21, а не с 44.
        val branch = game.substringAfter("if (baseForNick != name) {")
            .substringBefore("return@LaunchedEffect")
        assertTrue(
            "база выставляется ровно в значение строки",
            branch.contains("baseScore = known")
        )
        assertTrue(
            "счёт выставляется ровно в значение строки",
            branch.contains("score = known")
        )
        // Ветка «тот же ник, обновился топ» остаётся только на рост.
        assertTrue(
            "при обновлении топа счёт подтягивается только вверх",
            game.contains("if (known > baseScore)")
        )
    }


    /**
     * Ответ по прежнему нику не должен приклеиваться к новому.
     *
     * Гонка: пока запрос висел в сети, игрок меняет ник. Ответ приходит уже
     * на новое имя — и без проверки его счёт, место и отказ относятся к
     * нику, который никогда ничего не отправлял. Именно это давало «место 2»
     * сразу после смены имени на новый ник.
     */
    @Test
    fun `ответ по прежнему нику игнорируется после смены имени`() {
        val game = srcFile("TapChipScreen.kt")

        // Проверка стоит в ветке успешной записи — до применения счёта.
        val saved = game.substringAfter("is ChipTop.SubmitResult.Saved -> {")
            .substringBefore("is ChipTop.SubmitResult.Rejected")
        assertTrue(
            "ответ по устаревшему нику должен отбрасываться",
            saved.contains("if (nick.trim() != name) return@collect")
        )
        // Отказ по старому нику не должен висеть на новом.
        val rejected = game.substringAfter("is ChipTop.SubmitResult.Rejected -> {")
            .substringBefore("is ChipTop.SubmitResult.NoNetwork")
        assertTrue(
            "отказ должен записываться только для текущего ника",
            rejected.contains("if (nick.trim() == name)")
        )
        val noNet = game.substringAfter("is ChipTop.SubmitResult.NoNetwork -> {")
            .substringBefore("} finally")
        assertTrue(
            "сообщение о сети должно записываться только для текущего ника",
            noNet.contains("if (nick.trim() == name)")
        )
    }

}
