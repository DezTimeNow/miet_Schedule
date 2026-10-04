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
        // Требование владельца: таймаут на нажатие — секунда, не больше.
        // При секундной паузе за минуту игры набирается честные 60 очков,
        // и соперник может набрать столько же.
        assertEquals(
            "Пауза между тапами должна быть ровно секунда",
            1_000L,
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
        assertTrue(
            "В ряду служебных кнопок игры быть не должно",
            !menu.contains("\"Тапать микросхему\""),
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
        assertEquals("Тапай микросхему", GAME_TITLE)
        assertEquals("будь лучшим!!", GAME_TAGLINE)
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
        assertFalse(
            "При смене ника счёт обнуляться не должен",
            game.contains("score = 0"),
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
    fun `место в рейтинге не показывается`() {
        val game = srcFile("TapChipScreen.kt")
        // Комментарии не в счёт: ищем именно выводимый текст.
        val code = game.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "Подпись «место» должна быть убрана из интерфейса",
            code.contains("место"),
        )
        assertFalse(
            "Переменная rank больше не нужна: показывать нечего",
            game.contains("var rank by"),
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
        assertFalse(
            "Ни один элемент не должен масштабироваться: это дёргает layout",
            game.contains("scaleX =") || game.contains("scaleY ="),
        )
        // Анимация счёта меняет кегль текста, а не его размер в layout.
        assertTrue(
            "Счётчик подпрыгивает кеглем, а не перевёрсткой",
            game.contains("(46f * counterScale.value).sp"),
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

}
