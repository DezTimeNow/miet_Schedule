package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope

internal val MIET_BLUE = Color(0xFF0057B8)

/** Ключ Intent: какую группу открыть из уведомления. */
internal const val EXTRA_GROUP = "extra_group"

internal val DAY_NAMES = listOf(
    "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"
)
internal val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Номер дня недели Calendar → наш индекс (0=Пн … 6=Вс).
 *
 * Calendar считает неделю с воскресенья (SUNDAY=1 … SATURDAY=7), расписание —
 * с понедельника. Формула (cw + 5) % 7 даёт: Пн(2)→0, Вт(3)→1, Ср(4)→2,
 * Чт(5)→3, Пт(6)→4, Сб(7)→5, Вс(1)→6.
 *
 * Регрессия: раньше было `if (cw == SUNDAY) 0 else cw - 1`, то есть сдвиг на
 * единицу. 2 октября 2026 — пятница, cw=6, и приложение называло «Сб» вместо
 * «Пт», а карточки пар соседнего дня подсвечивались как сегодняшние.
 */
/**
 * Экран, который открывается при запуске.
 *
 * Вынесено отдельной функцией, а не оставлено внутри remember: правило
 * «сохранённая группа → сразу расписание» однажды стояло наоборот
 * (PICK_ENTITY), из-за чего приложение при каждом старте показывало
 * список групп вместо расписания. Правило проверяется тестом напрямую,
 * без запуска Activity.
 */
internal fun startScreenFor(hasSavedSelection: Boolean, requestedGroup: Boolean): Screen =
    if (hasSavedSelection || requestedGroup) Screen.SCHEDULE else Screen.PICK_ROLE

/**
 * Куда ведёт системная кнопка «Назад» и стрелка «‹».
 *
 * Один источник правды для всех пяти экранов. Раньше одна и та же логика
 * была продублирована в BackHandler, в переходах из «Избранного» и из
 * «О программе», и в двух местах ветвились по-разному: пустой выбор
 * приводил к падению на `selection!!` в ветке SCHEDULE.
 *
 * @param hasSelection выбрана ли группа/аудитория/преподаватель. На экране
 *   выбора роли она пустая, и оттуда «Назад» означает выход из приложения.
 */
internal fun backTargetFor(screen: Screen, hasSelection: Boolean): Screen = when (screen) {
    Screen.PICK_ENTITY -> Screen.PICK_ROLE
    Screen.SCHEDULE -> if (hasSelection) Screen.PICK_ENTITY else Screen.PICK_ROLE
    Screen.FAVORITES -> if (hasSelection) Screen.SCHEDULE else Screen.PICK_ROLE
    Screen.SETTINGS -> Screen.PICK_ROLE
    Screen.ABOUT -> if (hasSelection) Screen.PICK_ENTITY else Screen.PICK_ROLE
    // Отчёт открывается только из «О программе», поэтому назад — туда же
    // безусловно: выбранное расписание на это не влияет.
    Screen.REPORT -> Screen.ABOUT
    // Игра открывается из главного меню, поэтому назад — туда же.
    Screen.CHIP_GAME -> Screen.PICK_ROLE
    // С этого экрана BackHandler выключен, но стрелка может звать функцию.
    Screen.PICK_ROLE -> Screen.PICK_ROLE
}

internal fun dayIndexFromCalendar(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Аналитика и счётчик установок уходят в фоновый поток.
        //
        // AppMetrica.activate на главном потоке растягивал старт до 5 секунд:
        // SDK проверяет и загружает классы, и окно не получало фокус —
        // система фиксировала ANR «Waited 5021ms for FocusEvent». Это
        // повторялось на каждом запуске, а не как случайность. Отдельный
        // поток снимает задержку целиком; обе функции гасят свои ошибки
        // внутри и на работу приложения не влияют.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Analytics.start(this@MainActivity)
                InstallCounter.reportRun(this@MainActivity, BuildConfig.VERSION_CODE)
            }
        }

        // Выбор темы живёт здесь, а не в AppRoot: его должен менять экран
        // «О программе», а тот лежит внутри AppRoot. Передаём лямбду.
        var themeModeState by mutableStateOf(loadThemeMode(this))

        // Фоновая актуализация раз в 6 часов.
        //
        // ВАЖНО: schedule() трогает WorkManager, а его первая инициализация
        // читает базу и регистрирует компоненты — это десятки миллисекунд на
        // ГЛАВНОМ потоке. Вызов прямо здесь давал «Skipped 485 frames» и
        // ANR на старте (Waited 5014ms for FocusEvent). Поэтому регистрация
        // уходит в отдельный поток: она не влияет на первый кадр.
        Thread {
            runCatching { RefreshWorker.schedule(this) }
                .onFailure { Log.w("MainActivity", "Фоновая задача не зарегистрирована", it) }
        }.start()

        setContent {
            // Один диалог на всё приложение: его показывает и фоновая проверка
            // при запуске, и кнопка «Проверить обновления» в «О программе».
            val updateDialogHandle = remember { UpdateDialogHandle() }
            ScheduleTheme(themeMode = themeModeState) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        requestedGroup = intent?.getStringExtra(EXTRA_GROUP),
                        onUpdateFound = updateDialogHandle::show,
                        onThemeChange = { mode ->
                            saveThemeMode(this@MainActivity, mode)
                            themeModeState = mode
                        },
                    )
                // Проверка новой версии при запуске. Идёт в фоне: открытие
                // приложения не ждёт сеть, диалог всплывёт позже, если есть.
                UpdatePromptHost(
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    dialogHandle = updateDialogHandle,
                )
                }
            }
        }
    }
}

/**
 * Куда пользователь по выбору роли: сначала роль, потом своя сущность
 * (группа / преподаватель / аудитория), и только потом расписание.
 */
internal val RoleSaver = Saver<Role, String>(
    save = { it.key },
    restore = { key -> Role.fromKey(key) },
)

internal val ScreenSaver = Saver<Screen, String>(
    save = { it.name },
    restore = { key -> Screen.entries.firstOrNull { it.name == key } ?: Screen.PICK_ROLE },
)

// REPORT добавлен рядом с ABOUT: экран отчёта открывается из «О программе»
// и возвращается туда же. В ScreenSaver он попадает сам — Saver работает по
// it.name, а не по списку констант, поэтому новый экран в списке restore
// не нужен.
internal enum class Screen {
    PICK_ROLE, PICK_ENTITY, SCHEDULE, FAVORITES, SETTINGS, ABOUT, REPORT, CHIP_GAME,
}

@Composable
fun AppRoot(
    requestedGroup: String? = null,
    onUpdateFound: (UpdateInfo) -> Unit = {},
    onThemeChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val api = remember { MietApi(context) }
    val prefs = remember { GroupPrefs(context) }

    // СОСТОЯНИЕ ПРИ ПОВОРОТЕ ЭКРАНА. rememberSaveable переживает recreate
    // Activity, обычный remember — нет: при повороте роль, выбранная группа
    // и открытый экран возвращались к значениям из prefs. Роль и экран — enum,
    // а rememberSaveable умеет сохранять только типы, поддерживаемые Bundle,
    // поэтому для них заданы Saver (выше): наружу отдаётся строка.
    var role by rememberSaveable(stateSaver = RoleSaver) { mutableStateOf(prefs.role()) }
    // Выбор в рамках роли. Для преподавателя храним ФИО, для аудитории — имя
    // (по имени проще искать в избранном), код аудитории добираем из списка.
    // Код преподавателя и имя аудитории тоже переживают поворот. Раньше это
    // был обычный remember: роль и группа восстанавливались, а эти два поля
    // обнулялись, и у преподавателя после поворота переставал грузиться
    // индекс (refreshTeacher/lessonsOf получали пустой код), у аудитории
    // терялось имя для фильтра кэша.
    var teacherCode by rememberSaveable { mutableStateOf("") }
    var roomNameArg by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(requestedGroup ?: prefs.load()) }

    // СТАРТОВЫЙ ЭКРАН. При сохранённой роли и группе открываем сразу
    // расписание, а не экран выбора группы: группа уже выбрана и лежит
    // в prefs, и каждый запуск требовал лишнего тапа по ней. Раньше здесь
    // стояло PICK_ENTITY, из-за чего приложение открывалось на списке групп
    // даже при готовом кэше (sched_ИВТ-11 в miet_cache.xml). Без сохранённого
    // выбора — как и раньше, с экрана выбора роли.
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(startScreenFor(prefs.load() != null, requestedGroup != null))
    }
    // СИСТЕМНАЯ КНОПКА «НАЗАД». Проверено на эмуляторе: без этого перехвата
    // Android завершал Activity на всех пяти экранах (focus уходил на launcher),
    // потому что она была корнем стека. На экранах выбора группы и роли
    // стрелки «‹» в шапке либо нет, либо она ведёт на тот же экран, то есть
    // вернуться было нечем. Здесь те же переходы, что и у стрелки «‹».
    // На экране роли перехват выключен: там назад — выход из приложения.
    BackHandler(enabled = screen != Screen.PICK_ROLE) {
        screen = backTargetFor(screen, selection != null)
    }
    // Кнопка «Обновить» есть на всех экранах, поэтому состояние живёт здесь и
    // передаётся вниз — иначе каждый экран вёл бы свой счётчик.
    var refreshing by remember { mutableStateOf(false) }

    // СЧЁТЧИК ПОКОЛЕНИЙ. Это главное: refreshCurrent() пишет в кэш, но экран
    // расписания — отдельный composable со своим состоянием, и без сигнала он
    // продолжает показывать старое. Именно поэтому «Обновить» отработал за
    // 30 секунд, а на экране осталось «ещё не обновлялось».
    // Любое успешное обновление увеличивает счётчик, и ScheduleScreen по нему
    // перечитывает кэш и пересчитывает дату.
    var dataGeneration by remember { mutableIntStateOf(0) }

    // СОСТОЯНИЕ ЭКРАНА РАСПИСАНИЯ ДЛЯ ОТЧЁТА ОБ ОШИБКЕ.
    //
    // Отчёт об ошибке открывается из «О программе», когда расписание уже
    // закрыто, поэтому его состояние надо сохранить здесь: сколько пар
    // было на экране и какой текст ошибки показывался. Без этого письмо
    // содержало бы «пар: 0, ошибок нет» при любом реальном сбое — то есть
    // ровно то самое, ради чего отчёт и нужен.
    var reportLessons by remember { mutableIntStateOf(0) }
    var reportError by remember { mutableStateOf<String?>(null) }

    // Прогресс обновления, чтобы «30 секунд крутит» не выглядели зависанием.
    var refreshNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // ВОССТАНОВЛЕНИЕ КЛЮЧА РОЛИ ПРИ ХОЛОДНОМ СТАРТЕ.
    //
    // `selection` возвращается из prefs, а вот ключ, по которому расписание
    // находит преподавателя или аудиторию, — нет: `teacherCode` и
    // `roomNameArg` инициализируются пустыми. Для преподавателя это
    // терпимо (TeacherIndex.lessonsOf умеет искать по имени), но для
    // аудитории фатально: ScheduleData делает `teacherCode.toIntOrNull()`
    // и без кода показывает «Не удалось определить аудиторию», хотя имя
    // аудитории в prefs есть и экран запускается именно с ней.
    //
    // Замечено на 0.38 на эмуляторе: после force-stop и запуска аудитория
    // 1201 (м) открывалась с этой ошибкой, хотя через избранное работала.
    //
    // Значения пишутся в LaunchedEffect, а не прямо в теле composable:
    // rememberSaveable-переменные менять во время композиции нельзя —
    // это даёт нестабильное состояние и лишний повторный запуск эффекта.
    val startGroup = selection
    LaunchedEffect(startGroup, role) {
        if (startGroup == null) return@LaunchedEffect
        when (role) {
            Role.AUDIENCE -> if (teacherCode.isBlank()) {
                // Код аудитории ищем по имени в кэше списка аудиторий. Сети
                // здесь нет: список уже на диске, поэтому старт не задерживается.
                api.audienceCodeByName(startGroup)?.let { code ->
                    teacherCode = code.toString()
                    roomNameArg = startGroup
                }
            }
            Role.TEACHER -> if (teacherCode.isBlank()) {
                // Для преподавателя teacherCode — нормализованное ФИО.
                teacherCode = TeacherIndex.key(startGroup)
                roomNameArg = ""
            }
            Role.STUDENT -> {
                // У студента кода аудитории или преподавателя быть не должно:
                // иначе после смены роли в кэше ищется чужая комната.
                teacherCode = ""
                roomNameArg = ""
            }
        }
    }

    /**
     * Принудительное обновление данных под текущую роль.
     *
     * Для преподавателя это пересборка индекса (обход 344 групп), поэтому он
     * самый долгий; для студента и аудитории — точечное обновление. Ход работы
     * на IO-диспетчере: сетевой вызов из главного потока даёт
     * NetworkOnMainThreadException.
     */
    /**
     * ОБНОВЛЕНИЕ ВСЕГО, ЧТО ВИЖНО. Одна кнопка в любом меню.
     *
     * Раньше здесь было три разных поведения, и каждое обновляло не всё:
     * студент — свою группу, аудитория — список аудиторий, преподаватель —
     * индекс. Пользователь жал кнопку и получал «обновилось», а на экране
     * было по-прежнему. Теперь обновляется ВСЁ, независимо от роли:
     * список групп, расписания всех групп, список аудиторий, ответ по
     * выбранной аудитории, индекс преподавателей.
     *
     * 344 группы параллельно — около 35 секунд, поэтому показываем прогресс
     * («Обновляю… 120 из 344»), иначе это выглядит как зависшее приложение.
     */
    fun refreshCurrent() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            refreshNote = "Обновляю…"
            // Прогресс счётчика показываем отдельной корутиной на главном
            // потоке. Сам обход идёт в withContext(Dispatchers.IO), где пишет
            // в AtomicInteger, а надписью занимается только этот сборщик —
            // раньше refreshNote менялся прямо из шести потоков пула.
            val progress = java.util.concurrent.atomic.AtomicInteger(-1)
            val totalGroups = java.util.concurrent.atomic.AtomicInteger(0)
            val progressJob = launch {
                while (true) {
                    val n = progress.get()
                    if (n >= 0) refreshNote = "Обновляю… $n из ${totalGroups.get()}"
                    delay(300)
                }
            }
            val result = withContext(Dispatchers.IO) {
                // Счётчик обновляется из пула потоков, поэтому атомарный:
                // обычный `schedules++` из шести потоков терял инкременты, и
                // строка «Обновлено 287 из 343» показывала неверное число.
                val schedules = java.util.concurrent.atomic.AtomicInteger(0)
                var groupsN = 0
                runCatching {
                    // Список групп — с сайта, кэш не берём: кнопка обещает
                    // обновить вообще всё, а TTL в неделю прятал новые группы.
                    val groups = api.fetchGroups(force = true)
                    groupsN = groups.size
                    totalGroups.set(groups.size)
                    // Расписания групп — единственный источник для аудитории и
                    // преподавателя, поэтому обновляем их ВСЕГДА.
                    val total = groups.size
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    // Результаты копим В ПАМЯТИ и пишем в кэш одним пакетом.
                    //
                    // Раньше каждый из 343 запроса сам делал prefs.commit(), то
                    // есть 343 синхронных записи на диск посреди сетевого обхода.
                    // Замерено на эмуляторе: 120 групп за 208 секунд. Живым curl
                    // те же 343 группы скачиваются за 30 секунд — то есть тормозил
                    // не сайт, а запись кэша. Здесь сеть идёт чисто и параллельно.
                    val fresh = java.util.concurrent.ConcurrentHashMap<String, String>()
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                    try {
                        pool.invokeAll(groups.map { g ->
                            java.util.concurrent.Callable {
                                runCatching { api.fetchSchedule(g) }
                                    .onSuccess { fresh[g] = it; schedules.incrementAndGet() }
                                    .onFailure { Log.w("Refresh", "Не обновили $g: ${it.message}") }
                                // Здесь только счётчики: refreshNote — Compose
                                // State, и писать его из потоков пула нельзя
                                // (запись вне главного потока). Надписью
                                // занимается корутина-прогресс выше.
                                progress.set(done.incrementAndGet())
                            }
                        })
                    } finally {
                        pool.shutdown()
                    }
                    // Один commit() на все расписания вместо 343.
                    refreshNote = "Сохраняю…"
                    api.saveSchedules(fresh)
                    Log.i("Refresh", "В кэш записано ${fresh.size} расписаний одним пакетом")
                    // Списки и ответ по выбранной аудитории — тоже.
                    runCatching { api.fetchAudiences() }
                    teacherCode.toIntOrNull()?.let { code ->
                        runCatching { api.fetchAudience(code) }
                            .onFailure { Log.w("Refresh", "Аудитория $code: ${it.message}") }
                    }
                    // Индекс преподавателей пересобираем из ТОЛЬКО ЧТО
                    // скачанного кэша (buildFromCache), а не через TeacherIndex.build:
                    // тот ходит на miet.ru по всем 344 группам заново, и «обновление
                    // всего» превращалось в два полных прохода подряд. Отсюда же
                    // берётся метка индекса на экране «О программе».
                    runCatching { TeacherIndex.buildFromCache(api, groups) }
                        .onSuccess { Log.i("Refresh", "Индекс преподавателей: $it") }
                        .onFailure { Log.w("Refresh", "Индекс преподавателей: ${it.message}") }

                    // Начало семестра. Оно кэшируется на сутки, а кнопка
                    // «обновить всё» его не трогала: после смены семестра
                    // приложение целый день показывало недели от старой
                    // даты. Кнопка обещает актуализировать всё — значит и
                    // семестр тоже.
                    runCatching { api.semestrStart() }
                        .onSuccess { Log.i("Refresh", "Семестр с $it") }
                        .onFailure { Log.w("Refresh", "Семестр: ${it.message}") }

                    // Отметка «проверено» под текущим объектом. Без неё после
                    // обновления на экране оставалось «проверено давно»,
                    // хотя кэш только что перезаписан, и в отчёте об ошибке
                    // уходила неверная метка.
                    selection?.trim()?.takeIf { it.isNotEmpty() }?.let { sel ->
                        runCatching { api.markCheck(role, sel) }
                            .onFailure { Log.w("Refresh", "Метка проверки: ${it.message}") }
                    }

                    Log.i("Refresh", "Готово: расписаний ${schedules.get()} из $groupsN")
                }.onFailure {
                    Log.w("Refresh", "Обновление с ошибкой: ${it.message}")
                }
                schedules.get() to groupsN
            }
            progressJob.cancel()
            // Сигнал экрану перечитать кэш. Без этого он показывал бы старое.
            dataGeneration++

            // Напоминания строятся по избранным группам, то есть по кэшу.
            // Кэш перезаписан, а будильники остались на старых временах:
            // человек получил звонок по расписанию, которого уже нет.
            // Пересчёт читает и разбирает JSON, поэтому идёт на IO.
            //
            // Раньше здесь ничего не было — перепланирование вызывал только
            // RefreshWorker, то есть кнопка обновления будильники не трогала.
            if (result.first > 0) {
                withContext(Dispatchers.IO) {
                    runCatching { ReminderScheduler.reschedule(context, api) }
                        .onFailure { Log.w("Refresh", "Напоминания не перепланированы: ${it.message}") }
                }
            }
            refreshNote = if (result.first > 0) {
                "Обновлено ${result.first} из ${result.second}"
            } else "Ничего не обновилось"
            refreshing = false
        }
    }


    when (screen) {
        Screen.PICK_ROLE -> RolePickerScreen(
            current = if (prefs.load() != null) role else null,
            onRefresh = { refreshCurrent() },
            onChangeRole = { screen = Screen.PICK_ROLE },
            refreshNote = refreshNote,
            onAbout = { screen = Screen.ABOUT },
            onSettings = { screen = Screen.SETTINGS },
            onOpenFavorites = { screen = Screen.FAVORITES },
            onOpenChipGame = { screen = Screen.CHIP_GAME },
            refreshing = refreshing,
            onPick = { r ->
                prefs.saveRole(r)
                role = r
                selection = prefs.loadFor(r)
                teacherCode = ""
                screen = Screen.PICK_ENTITY
            }
        )

        Screen.PICK_ENTITY -> {
            when (role) {
                Role.STUDENT -> GroupPickerScreen(
                    api, prefs, selection,
                    onChosen = { chosen ->
                        selection = chosen
                        // Группу нужно сохранить: без этого при следующем
                        // запуске prefs.load() пуст, приложение открывается на
                        // выборе роли, и каждый раз группу приходится выбирать
                        // заново. Преподаватель и аудитория здесь сохраняют
                        // своё значение — студент не сохранял ничего.
                        prefs.save(chosen)
                        screen = Screen.SCHEDULE
                    },
                    onSwitchRole = { screen = Screen.PICK_ROLE },
                    onRefresh = { refreshCurrent() },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                )
                Role.TEACHER -> TeacherPickerScreen(
                    api, prefs,
                    onChosen = { name, code ->
                        selection = name
                        teacherCode = code
                        // Ключ преподавателя — нормализованное ФИО, а показываем
                        // исходное: в избранном нужно видеть «Лупин Сергей Сергеевич»,
                        // а не внутренний ключ в нижнем регистре.
                        prefs.save(name)
                        screen = Screen.SCHEDULE
                    },
                    onBack = { screen = Screen.PICK_ROLE },
                    // Раньше здесь передавались ТОЛЬКО onChosen и onBack.
                    // onRefresh не передавался, поэтому срабатывал его
                    // аргумент по умолчанию `= {}` из сигнатуры: кнопка в шапке нажималась и ничего
                    // не делала — «преподаватель ещё не выбран, обновление
                    // не жмётся». Заодно не передавались «Роль», refreshing
                    // и прогресс, то есть шапка у этой роли отличалась.
                    onRefresh = { refreshCurrent() },
                    onChangeRole = { screen = Screen.PICK_ROLE },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                )
                Role.AUDIENCE -> AudiencePickerScreen(
                    api, prefs,
                    onChosen = { code, name ->
                        selection = name
                        teacherCode = code.toString()
                        // Имя аудитории нужно и для фильтра кэша: код из
                        // /audiences — внутренний id, а не номер (у «8307»
                        // id 234), и по коду расписание находилось не полностью.
                        roomNameArg = name
                        prefs.save(name)
                        screen = Screen.SCHEDULE
                    },
                    onBack = { screen = Screen.PICK_ROLE },
                    onRefresh = { refreshCurrent() },
                    onChangeRole = { screen = Screen.PICK_ROLE },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                    onAbout = { screen = Screen.ABOUT },
                )
            }
        }

        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = Screen.PICK_ROLE },
            onRefresh = { refreshCurrent() },
            onChangeRole = { screen = Screen.PICK_ROLE },
            onThemeChange = onThemeChange,
            refreshing = refreshing,
        )

        Screen.ABOUT -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onBack = { screen = backTargetFor(Screen.ABOUT, selection != null) },
            onUpdateFound = onUpdateFound,
            onRefresh = { refreshCurrent() },
            onChangeRole = { screen = Screen.PICK_ROLE },
            onReport = { screen = Screen.REPORT },
            refreshing = refreshing,
            refreshNote = refreshNote,
            // Тема переехала в «Настройки», поэтому здесь её переключателя
            // нет и лямбда не передаётся.
        )

        
        // ───────── «Сообщить об ошибке» ─────────
        //
        // Данные отчёта собираются здесь, а не на экране отчёта: сведения о
        // состоянии расписания живут в AppRoot (роль, выбор) и в кэше, и
        // экран отчёта должен показать то, что было на момент нажатия.
        Screen.REPORT -> ReportScreen(
            report = ReportData(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                role = role,
                selection = selection,
                lessonsShown = reportLessons,
                errorText = reportError,
                updateCheckedAt = UpdateChecker.lastCheckedAt(context),
            ),
            onBack = { screen = Screen.ABOUT },
            // ⭯ в шапке отчёта проверяет обновления приложения.
            onCheckUpdate = {
                scope.launch {
                    when (val r = UpdateChecker.checkNow(context, BuildConfig.VERSION_CODE)) {
                        is UpdateCheckResult.Available -> onUpdateFound(r.info)
                        is UpdateCheckResult.UpToDate -> Log.i("Report", "Свежая версия")
                        is UpdateCheckResult.Failed -> Log.w("Report", r.reason)
                    }
                }
            },
        )

        // ───────── мини-игра «Тапни микросхему» ─────────
        //
        // Отдельный экран без параметров: он не зависит от выбранной роли
        // или группы, а результат уходит на скрипт таблицы лидеров.
        Screen.CHIP_GAME -> TapChipScreen(onBack = { screen = Screen.PICK_ROLE })

Screen.FAVORITES -> FavoritesScreen(
            prefs = prefs,
            onOpen = { r, value ->
                // Открытие из избранного. Здесь восстанавливается всё, что нужно
                // расписанию, а не только имя: у преподавателя ключ поиска — это
                // нормализованное ФИО, у аудитории нужен ещё и внутренний код.
                // Раньше здесь стояли `teacherCode = ""` и переход в PICK_ENTITY,
                // из-за чего преподаватель показывал «Пар не найдено», аудитория —
                // «Не удалось определить аудиторию», а студент после выбора
                // попадал в список групп и должен был жать на группу ещё раз.
                prefs.saveRole(r)
                role = r
                when (r) {
                    Role.STUDENT -> {
                        selection = value
                        teacherCode = ""
                        roomNameArg = ""
                    }
                    Role.TEACHER -> {
                        // teacherCode здесь — не числовой код, а нормализованное
                        // ФИО: именно с ним TeacherIndex.lessonsOf сравнивает
                        // преподавателя в кэше расписаний.
                        selection = value
                        teacherCode = TeacherIndex.key(value)
                        roomNameArg = ""
                    }
                    Role.AUDIENCE -> {
                        // Имя нужно само по себе для фильтра кэша по комнате,
                        // код — для запроса к серверу. В избранном хранится имя,
                        // код ищем в кэше списка аудиторий.
                        selection = value
                        roomNameArg = value
                        teacherCode = api.audienceCodeByName(value)?.toString().orEmpty()
                    }
                }
                prefs.saveFor(r, selection ?: "")
                screen = Screen.SCHEDULE
            },
            onBack = { screen = backTargetFor(Screen.FAVORITES, selection != null) },
            onRefresh = { refreshCurrent() },
            onChangeRole = { screen = Screen.PICK_ROLE },
            refreshing = refreshing,
            refreshNote = refreshNote,
        )

        // Расписание без выбранной сущности показывать нечем: экран взял бы
        // пустую группу. Раньше здесь стояло selection!!, и любой путь,
        // обнулявший выбор, приводил к падению. Теперь это тихий переход
        // на выбор роли вместо исключения.
        Screen.SCHEDULE -> ScheduleBody(
            api = api,
            prefs = prefs,
            role = role,
            selection = selection,
            onNoSelection = { screen = Screen.PICK_ROLE },
            teacherCode = teacherCode,
            roomName = roomNameArg,
            dataGeneration = dataGeneration,
            onRefreshAll = { refreshCurrent() },
            refreshNote = refreshNote,
            // Общее обновление идёт около 30 секунд. Без этого кнопка ⭯ на
            // экране расписания оставалась активной и нажималась повторно,
            // начиная второй круг обхода поверх первого.
            refreshingAll = refreshing,
            onChangeEntity = { screen = Screen.PICK_ENTITY },
            // Из избранного аудитория открывается по имени (value), а код
            // неизвестен — фильтр кэша отработает по имени, это верно.
            onChangeRole = { screen = Screen.PICK_ROLE },
            onScreenState = { n, err -> reportLessons = n; reportError = err },
        )
    }
}

/**
 * Экран расписания с защитой от пустого выбора.
 *
 * Раньше AppRoot писал `group = selection!!`: если выбор оказывался пустым,
 * приложение падало. Пустой выбор достижим — «Избранное» открывается и с
 * экрана выбора роли, где ничего ещё не выбрано. Здесь вместо исключения
 * тихий переход на экран выбора роли.
 */
@Composable
private fun ScheduleBody(
    api: MietApi,
    prefs: GroupPrefs,
    role: Role,
    selection: String?,
    onNoSelection: () -> Unit,
    teacherCode: String,
    roomName: String,
    dataGeneration: Int,
    onRefreshAll: () -> Unit,
    refreshNote: String,
    /** Идёт ли общее обновление по ⭯ — нужно для крутилки в шапке. */
    refreshingAll: Boolean = false,
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    onScreenState: (Int, String?) -> Unit = { _, _ -> },
) {
    val group = selection
    if (group == null) {
        LaunchedEffect(Unit) { onNoSelection() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    ScheduleScreen(
        api = api,
        prefs = prefs,
        role = role,
        group = group,
        teacherCode = teacherCode,
        roomName = roomName,
        dataGeneration = dataGeneration,
        onRefreshAll = onRefreshAll,
        refreshNote = refreshNote,
        refreshingAll = refreshingAll,
        onChangeEntity = onChangeEntity,
        onChangeRole = onChangeRole,
        onScreenState = onScreenState,
    )
}


// ───────────────────────────── расписание ─────────────────────────────
