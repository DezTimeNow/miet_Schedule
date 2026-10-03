package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator

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
    Screen.ABOUT -> if (hasSelection) Screen.PICK_ENTITY else Screen.PICK_ROLE
    // С этого экрана BackHandler выключен, но стрелка может звать функцию.
    Screen.PICK_ROLE -> Screen.PICK_ROLE
}

internal fun dayIndexFromCalendar(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            // Один диалог на всё приложение: его показывает и фоновая проверка
            // при запуске, и кнопка «Проверить обновления» в «О программе».
            val updateDialogHandle = remember { UpdateDialogHandle() }
            MaterialTheme(colorScheme = lightColorScheme(primary = MIET_BLUE)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F7FA)) {
                    AppRoot(
                        requestedGroup = intent?.getStringExtra(EXTRA_GROUP),
                        onUpdateFound = updateDialogHandle::show,
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

internal enum class Screen { PICK_ROLE, PICK_ENTITY, SCHEDULE, FAVORITES, ABOUT }

@Composable
fun AppRoot(
    requestedGroup: String? = null,
    onUpdateFound: (UpdateInfo) -> Unit = {},
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

    // Прогресс обновления, чтобы «30 секунд крутит» не выглядели зависанием.
    var refreshNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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
                    Log.i("Refresh", "Готово: расписаний ${schedules.get()} из $groupsN")
                }.onFailure {
                    Log.w("Refresh", "Обновление с ошибкой: ${it.message}")
                }
                schedules.get() to groupsN
            }
            progressJob.cancel()
            // Сигнал экрану перечитать кэш. Без этого он показывал бы старое.
            dataGeneration++
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
                    refreshNote = refreshNote
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

        Screen.ABOUT -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onBack = { screen = backTargetFor(Screen.ABOUT, selection != null) },
            onUpdateFound = onUpdateFound,
            onRefresh = { refreshCurrent() },
            onChangeRole = {
                prefs.clear(); selection = null; teacherCode = ""; roomNameArg = ""
                screen = Screen.PICK_ROLE
            },
            refreshing = refreshing,
            refreshNote = refreshNote,
        )

        Screen.FAVORITES -> FavoritesScreen(
            prefs = prefs,
            onOpen = { r, value ->
                role = r
                selection = value
                teacherCode = ""
                screen = Screen.PICK_ENTITY
            },
            // Раньше здесь стояло безусловное Screen.SCHEDULE. Но «Избранное»
            // открывается и с экрана выбора роли, где selection == null, — а
            // ветка SCHEDULE берёт group = selection!! и падала бы. Теперь
            // переход тот же, что и в BackHandler: при пустом выборе идём
            // на выбор роли.
            onBack = { screen = backTargetFor(Screen.FAVORITES, selection != null) },
            onRefresh = { refreshCurrent() },
            onChangeRole = {
                prefs.clear(); selection = null; teacherCode = ""; roomNameArg = ""
                screen = Screen.PICK_ROLE
            },
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
            onChangeEntity = { screen = Screen.PICK_ENTITY },
            onOpenFavorites = { screen = Screen.FAVORITES },
            // Из избранного аудитория открывается по имени (value), а код
            // неизвестен — фильтр кэша отработает по имени, это верно.
            onChangeRole = {
                prefs.clear()
                selection = null
                teacherCode = ""
                roomNameArg = ""
                screen = Screen.PICK_ROLE
            }
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
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    onOpenFavorites: () -> Unit,
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
        onChangeEntity = onChangeEntity,
        onChangeRole = onChangeRole,
        onOpenFavorites = onOpenFavorites,
    )
}

// ───────────────────── выбор группы: направление → группа ─────────────────────


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    currentGroup: String?,
    onChosen: (String) -> Unit,
    onSwitchRole: () -> Unit = {},
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = ""
) {
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openCode by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(setOf<String>()) }

    // Избранное перечитываем при каждом входе на экран
    LaunchedEffect(Unit) {
        favs = groups.filter { prefs.isFav(it) }.toSet()
    }
    // Если список групп ещё не подгружен, а избранное уже есть — дочитываем
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) favs = groups.filter { prefs.isFav(it) }.toSet()
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { api.cachedGroups() }
        if (cached.isNotEmpty()) { groups = cached; loading = false }
        runCatching { withContext(Dispatchers.IO) { api.fetchGroups() } }
            .onSuccess { groups = it; loading = false }
            .onFailure {
                Log.w("MietPicker", "Не удалось загрузить группы", it)
                if (groups.isEmpty()) {
                    error = "Не удалось загрузить список групп: ${it.message}"
                    loading = false
                }
            }
    }

    val byCode = remember(groups, query) {
        val filtered = if (query.isBlank()) groups
        else groups.filter { it.contains(query.trim(), ignoreCase = true) }
        Faculties.groupByCode(filtered)
    }
    val total = byCode.values.sumOf { it.size }
    val favList = remember(favs, groups) { favs.sorted() }

    Scaffold(
        topBar = {
            // Раньше здесь стоял текст «Сменить роль» СЛЕВА от кнопки обновления.
            // Он шире, чем стрелка «‹» у преподавателя и аудитории, и из-за
            // этого обновление на экране студента уезжало вправо относительно
            // остальных ролей. Теперь шапка общая — MietTopBar, стрелка «‹»
            // ведёт к выбору роли.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "Выбери свою группу",
                onRefresh = onRefresh,
                onChangeRole = onSwitchRole,
                refreshing = refreshing,
                onBack = onSwitchRole
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; openCode = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск: ИВТ, ЭН, ИС, Колледж…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            when {
                loading && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = Color(0xFFC62828), fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                        Button(onClick = { error = null; loading = true }) { Text("Повторить") }
                    }
                }
                else -> {
                    Text(
                        if (query.isBlank()) "$total ${plural(total, "группа", "группы", "групп")} в ${byCode.size} ${plural(byCode.size, "маркировке", "маркировках", "маркировках")}"
                        else "Найдено: $total",
                        fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {

                        // ── Избранное ──
                        if (favList.isNotEmpty() && query.isBlank()) {
                            item(key = "favhdr") {
                                FavHeader(count = favList.size)
                            }
                            item(key = "favlist") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        favList.chunked(2).forEach { pair ->
                                            Row(Modifier.fillMaxWidth()) {
                                                pair.forEach { g ->
                                                    FavChip(g, Modifier.weight(1f)) { prefs.save(g); onChosen(g) }
                                                }
                                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        byCode.forEach { (code, list) ->
                            item(key = "code_$code") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    openCode = if (openCode == code) null else code
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(code, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Text("${list.size} ${plural(list.size, "группа", "группы", "групп")}", fontSize = 11.sp, color = Color.Gray)
                                            }
                                            Text(
                                                if (openCode == code) "\u2212" else "+",
                                                fontSize = 22.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                            )
                                        }

                                        AnimatedVisibility(visible = openCode == code) {
                                            Column(Modifier.padding(bottom = 6.dp)) {
                                                list.forEach { g ->
                                                    GroupRow(
                                                        group = g,
                                                        isFav = g in favs,
                                                        isCurrent = g == currentGroup,
                                                        onClick = { prefs.save(g); onChosen(g) },
                                                        onFav = { favs = favs.toggle(g, prefs) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Русское склонение по числу: 1 группа / 2 группы / 5 групп.
 * Для существительных [one] — форма для 1, [few] — для 2-4, [many] — для остальных.
 * Правило: последние две цифры 11-14 (кроме 11-13 в 11-14) всегда считаются как "много".
 */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val abs = Math.abs(n) % 100
    return when {
        abs in 11..14 -> many
        abs % 10 == 1 -> one
        abs % 10 in 2..4 -> few
        else -> many
    }
}

private fun Set<String>.toggle(g: String, prefs: GroupPrefs): Set<String> {
    val next = toMutableSet()
    if (prefs.toggleFav(g)) next.add(g) else next.remove(g)
    return next
}

@Composable
private fun FavHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★ Избранное", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun FavChip(group: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier.padding(3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFECB3))
    ) {
        Text(
            group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}


@Composable
private fun GroupRow(
    group: String,
    isFav: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onFav: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) Color(0xFFBBDEFB) else Color(0xFFE3F2FD)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { onFav() }) {
                Text(
                    if (isFav) "★" else "☆",
                    fontSize = 17.sp, color = if (isFav) Color(0xFFE65100) else Color(0xFFBDBDBD)
                )
            }
        }
    }
}

// ───────────────────────────── расписание ─────────────────────────────

/**
 * Расписание выбранной сущности.
 *
 * [role] решает, откуда берутся данные: у студента — своя группа с сервера,
 * у аудитории — её сетка занятости, у преподавателя — сборка из кэша расписаний
 * всех групп (сайт его расписание не отдаёт, см. [TeacherIndex]).
 *
 * [teacherCode] заполнен только для преподавателя, [group] хранит ФИО.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    api: MietApi,
    prefs: GroupPrefs,
    role: Role,
    group: String,
    teacherCode: String,
    roomName: String = "",
    dataGeneration: Int = 0,
    onRefreshAll: () -> Unit = {},
    refreshNote: String = "",
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    onOpenFavorites: () -> Unit = {}
) {
    // Данные и загрузка вынесены в rememberScheduleData (ScheduleData.kt):
    // здесь остаётся только отрисовка. Раньше те же 190 строк загрузки
    // стояли в теле экрана вместе с UI.
    // Метка «Обновлено …». Объявлена до вызова rememberScheduleData: тот же
    // экран читает значение здесь, а load() сообщает новую дату через
    // onLoaded — раньше метка отставала от фактической записи в кэш.
    var lastUpdated by remember { mutableStateOf(0L) }
    val data = rememberScheduleData(api, role, group, teacherCode, roomName) { ts ->
        lastUpdated = ts
    }
    val lessons = data.lessons
    val times = data.times
    val semestr = data.semestr
    val loading = data.loading
    val refreshing = data.refreshing
    val error = data.error
    val load = data.load
    // ─── СВЯЗКА «СЕГОДНЯ» И НЕДЕЛИ ───
    //
    // Фильтр «Сегодня» имеет смысл только на текущей неделе. Кнопки ‹ ›
    // листают учебную неделю, а showWeek жил своей жизнью: сдвинул неделю —
    // остался в режиме «Сегодня», где todayLessons при weekOffset != 0 пуст
    // по определению (isCurrentWeek == false), и экран показывал
    // «Пятница 9.10.2026 пар нет» вместо расписания недели.
    //
    // Теперь сдвиг недели САМ снимает фильтр и показывает всю неделю.
    // Обратно — нажатием на «Сегодня»: восстановить неделю 0 вручную
    // кнопкой «сейчас», потом переключить вкладку. Кнопка «Сегодня» —
    // единственное место, где showWeek задаётся напрямую.
    // Фильтр недели переживает поворот экрана: rememberSaveable вместо
    // remember, иначе recreate Activity возвращал экран на «Сегодня» и
    // сбрасывал выбранную учебную неделю на текущую.
    var showWeek by rememberSaveable { mutableStateOf(false) }
    // Метка «обновлено …» из кэша. Держим в state, а не читаем напрямую:
    // после нажатия «Обновить» значение должно смениться на глазах.
    var weekName by remember { mutableStateOf(WeekType.currentName()) }
    var weekRow by remember { mutableStateOf(WeekType.currentRowIndex()) }
    // Сдвиг учебной недели для кнопок «‹ неделя ›». 0 = текущая, +1 = следующая.
    // Хранится именно сдвиг, а не абсолютный тип недели: при смене семестра
    // абсолютное значение устареет, а сдвиг 0 всегда означает «текущая».
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    // Начало семестра нужно и для пересчёта сдвига, иначе после смены семестра
    // weekRow уедет. Кэшируем один раз на запуск экрана.
    var semestrStartIso by remember { mutableStateOf(WeekType.SEMESTR_START_ISO) }

    // API отдаёт Day 1..6 (Пн..Сб). Наш индекс: 0=Пн … 6=Вс.
    //
    // Calendar.DAY_OF_WEEK: SUNDAY=1, MONDAY=2, … SATURDAY=7 — то есть
    // неделя начинается с воскресенья, а нам нужно с понедельника. Формула
    // (cw + 5) % 7: Пн(2)→0, Вт(3)→1, … Сб(7)→5, Вс(1)→6.
    //
    // БЫЛО `if (cw == SUNDAY) 0 else cw - 1` — это сдвиг на единицу: для
    // пятницы cw=7 даёт 6 = «Вс», а показывало «Сб» из-за того же сдвига.
    // Из-за этого «Сегодня» называло неверный день и карточки чужих пар
    // подсвечивались как сегодняшние.
    // todayDay пересчитывается при каждом показе экрана, а не один раз:
    // раньше было remember без ключа, и если приложение висело открытым
    // через полночь, «Сегодня» продолжало называть вчерашний день.
    val todayDay = dayIndexFromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
    val scope = rememberCoroutineScope()

    // Звезда в шапке расписания. prefs.isFav() — обычная функция, Compose о ней
    // не знает, поэтому её значение не пересчитывается само. Держим флаг в
    // state и перечитываем из хранилища после каждого переключения, иначе
    // звезда «нажимается», но визуально не меняется.
    var isFav by remember(group, role) { mutableStateOf(prefs.isFavFor(role, group)) }
    fun refreshFav() { isFav = prefs.isFavFor(role, group) }
    // Возврат на экран расписания мог оставить звезду устаревшей.
    LaunchedEffect(group, role) { refreshFav() }

    // Актуализируем неделю при запуске и возврате на экран
    LaunchedEffect(group) {
        val ss = withContext(Dispatchers.IO) { api.semestrStart() }
        semestrStartIso = ss
        weekOffset = 0
        // showWeek здесь НЕ трогаем: вход на экран расписания — это
        // «открыл посмотреть сегодня». Снятие фильтра относится только к
        // ПЕРЕХОДУ на другую неделю. Иначе приложение открывалось бы сразу
        // на «Вся неделя» (18 пар вместо сегодняшних двух), а стартовое
        // поведение менять нельзя.
        weekName = WeekType.currentName(ss)
        weekRow = WeekType.currentRowIndex(ss)
    }

    /** Пересчитать метку последнего обновления из кэша. */
    fun refreshLastUpdated() {
        scope.launch {
            val ts = withContext(Dispatchers.IO) { api.lastUpdatedAt(role, group) }
            lastUpdated = ts
        }
    }

    // Дата последнего обновления. Перечитываем и при смене роли/объекта, и
    // после каждой загрузки — иначе после «Обновить» осталась бы старая дата.
    LaunchedEffect(group, role) { refreshLastUpdated() }



    LaunchedEffect(group, teacherCode, role) { load(false) }

    // Поколение данных выросло — перечитываем кэш и пересчитываем дату.
    // Именно этого не хватало: refreshCurrent() писал в кэш, но этот экран
    // был отдельным composable со своим состоянием и показывал старое.
    LaunchedEffect(dataGeneration) {
        if (dataGeneration > 0) {
            Log.i("Schedule", "Поколение $dataGeneration — перечитываю")
            // Именно false, а не true: расписания уже обновлены выше, в
            // refreshCurrent(). С true экран аудитории уходил в СВОЙ второй
            // полный проход по 344 группам — последовательно, по одному, от
            // чего обновление на 344 группы занимало минуты вместо 35 секунд,
            // а кнопка выглядела зависшей. Здесь нужен только пересчёт кэша.
            load(false)
            // Дату считаем здесь, ПОСЛЕ load. Внутри load() она считалась в
            // конце, но ветка аудитории возвращалась из кэша раньше, чем
            // долетели записи расписаний, и подпись отставала на минуты.
            // Повтор с задержкой: пока идёт запись 344 расписаний, метка
            // едет вперёд — ловим последнюю, уже после записи.
            repeat(3) {
                val ts = withContext(Dispatchers.IO) { api.lastUpdatedAt(role, group) }
                lastUpdated = ts
                delay(1200)
            }
        }
    }

    // Только строка текущей учебной недели (DayNumber == weekType).
    // Фильтр по чётности тут давал бы дубли: строки 0 и 2 у части групп
    // содержат одинаковые пары, а у ИВТ-11/ПИН-11 — разные.
    // Короткая дата «02.10» для компактных подписей (табы, чипы).
    fun dateShort(day: Int, offset: Int, startIso: String): String {
        val d = WeekType.dateOfWeekDay(offset, day, startIso) ?: return ""
        return String.format("%02d.%02d", d.get(Calendar.DAY_OF_MONTH), d.get(Calendar.MONTH) + 1)
    }

    val activeWeekRow = remember(weekOffset, semestrStartIso) {
        WeekType.shiftedRow(weekOffset, semestrStartIso)
    }

    // Диапазон дат выбранной недели: «29.09 — 05.10».
    fun weekRangeLabel(): String {
        val mon = WeekType.dateOfWeekDay(weekOffset, 0, semestrStartIso)
            ?: return WeekType.name(activeWeekRow)
        val sun = WeekType.dateOfWeekDay(weekOffset, 6, semestrStartIso) ?: return ""
        fun f(d: java.util.Calendar) =
            String.format("%02d.%02d", d.get(java.util.Calendar.DAY_OF_MONTH), d.get(java.util.Calendar.MONTH) + 1)
        return "${f(mon)} \u2014 ${f(sun)}"
    }

    val weekLessons = remember(lessons, activeWeekRow) {
        lessons.filter { (it.dayNumber ?: 0) == activeWeekRow }
    }
    // Ключи byDay и индекс todayDay должны быть НА ОДНОЙ шкале.
    //
    // Сервер отдаёт Day = 1..6 (1=Пн … 6=Сб) — это 1-based. А dayIndexFromCalendar
    // возвращает 0..6 (0=Пн … 6=Вс) — 0-based. Смешивать их нельзя.
    //
    // БЫЛО: groupBy { it.day } (1..6) и byDay[todayDay] (0..6) — минус единица
    // на ровно одно место. В пятницу todayDay=4, и приложение показывало
    // byDay[4] — то есть ЧЕТВЕРГ, в шапке писало «Четверг», а карточки
    // четверга подсвечивались как сегодняшние. Отсюда «у Кузнецова сегодня
    // четверг», хотя на самом деле пятница.
    //
    // Теперь приводим к нашей шкале 0..6 сразу при группировке, а в шапке
    // берём DAY_NAMES[day] без «-1».
    val byDay = weekLessons.groupBy { (it.day ?: 1) - 1 }.toSortedMap()
    // «Сегодня» имеет смысл ТОЛЬКО на текущей неделе.
    //
    // Кнопки ‹ › листают учебную неделю, но todayDay остаётся пятницей
    // навсегда. Из-за этого на следующей неделе подсвечивалась пятница
    // 09.10 и писалось «— сегодня», хотя сейчас всё ещё 02.10. При сдвиге
    // показываем обычную неделю без подсветки, а вкладка называется не
    // «Сегодня», а по дате выбранной недели.
    val isCurrentWeek = weekOffset == 0
    val markedDay = if (isCurrentWeek) todayDay else -1
    val todayLessons = if (isCurrentWeek) byDay[todayDay].orEmpty() else emptyList()

    Scaffold(
        topBar = {
            MietTopBar(
                title = group,
                // П.5. Роль («Студент»/«Преподаватель»/«Аудитория») из подписи
                // убрана: её и так показывает галочка и цвет в списке ролей,
                // а на главной она была вторым упоминанием того же самого
                // прямо над собой. Осталось только семестр — он своё
                // обозначение не дублирует.
                subtitle = if (semestr.isBlank()) "Загрузка…" else semestr,
                onRefresh = onRefreshAll,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                // Назад — к списку своей сущности (смена группы/аудитории).
                onBack = onChangeEntity,
                // Звезда уехала из строки вкладок в шапку: там она стояла
                // ПРАВЕЕ «Сегодня»/«Вся неделя» и при смене ширины подписей
                // съезжала. В шапке у неё фиксированный слот.
                isFav = isFav,
                onToggleFav = { prefs.toggleFavFor(role, group); refreshFav() },
                onOpenFavorites = onOpenFavorites
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // ─── строка «обновлено …» ───
            // Кнопки здесь больше нет: она живёт в шапке, одна и та же на всех
            // экранах (просьба: «где бы я ни находился, обновлялось всё»).
            // Здесь остались дата и прогресс — чтобы 30 секунд ожидания
            // показывали счётчик, а не выглядели зависанием.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Обновлено: ${LastUpdated.label(lastUpdated)}",
                    fontSize = 11.sp,
                    color = Color(0xFF78909C),
                    modifier = Modifier.weight(1f),
                )
                if (refreshNote.isNotEmpty()) {
                    Text(
                        refreshNote,
                        fontSize = 11.sp,
                        color = if (refreshing) MIET_BLUE else Color(0xFF78909C),
                    )
                }
            }

            // ─── строка учебной недели ───
            //
            // Раньше переключатель недели жил в шапке вместе с шестью другими
            // кнопками и не влезал. Здесь ему место: слева «‹», по центру —
            // семестр, тип недели и дата понедельника, справа «›» и сброс.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    // Сдвиг недели САМ снимает фильтр «Сегодня»: сдвинутая
                    // неделя — это обзор недели, а не «сегодня». Поэтому
                    // showWeek = true в обоих направлениях. Обратно фильтр
                    // возвращается только нажатием на «Сегодня».
                    onClick = { weekOffset--; showWeek = true },
                    enabled = weekOffset > -4
                ) {
                    Text("\u2039", color = MIET_BLUE, fontSize = 22.sp)
                }
                Text(
                    buildString {
                        if (semestr.isNotBlank()) { append(semestr); append(" • ") }
                        append(WeekType.name(activeWeekRow))
                        append(" • ")
                        append(weekRangeLabel())
                    },
                    fontSize = 13.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(
                    onClick = { weekOffset++ ; showWeek = true },
                    enabled = weekOffset < 4
                ) {
                    Text("\u203A", color = MIET_BLUE, fontSize = 22.sp)
                }
                // Сброс на текущую неделю — только когда со сдвига. Стоит
                // ПОСЛЕ «›»: иначе при первом нажатии «›» он появлялся и
                // сдвигал «›» влево, и второе нажатие попадало уже в него.
                // Кнопка «сейчас» РАНЬШЕ появлялась только при сдвиге недели.
                // Она была выше строки вкладок, поэтому всё расписание
                // уезжало вниз на высоту кнопки — интерфейс «прыгал» при
                // каждом нажатии «›».
                //
                // Теперь место под неё зарезервировано ВСЕГДА: пустой Spacer
                // той же ширины, когда сдвига нет. Высота строки постоянна,
                // нажатие ничего не двигает, а кнопка появляется по запросу.
                if (weekOffset != 0) {
                    // «сейчас» возвращает текущую неделю И снимает фильтр
                    // «Сегодня»: показывается вся неделя. Возврат в режим
                    // «Сегодня» — отдельное нажатие на саму вкладку.
                    TextButton(onClick = { weekOffset = 0; showWeek = true }) {
                        Text("сейчас", fontSize = 12.sp, color = MIET_BLUE)
                    }
                } else {
                    Spacer(Modifier.width(56.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Ширина вкладок ФИКСИРОВАНА: без неё «Вся неделя» уезжала
                // влево вместе с подписью первой вкладки, и интерфейс
                // «прыгал» вбок при каждом нажатии «›».
                //
                // Подпись вкладки ВСЕГДА с датой сегодняшнего дня. Раньше на
                // сдвинутой неделе она становилась «Пт 09.10» — то есть показывала
                // дату ПЕРВОЙ пары в выбранной неделе, а вкладка при этом была
                // нерабочей. Теперь подпись описывает фактическое действие
                // кнопки: возврат на сегодняшний день.
                FilterChip(
                    modifier = Modifier.width(196.dp),
                    selected = !showWeek,
                    // Кнопка «Сегодня» ВСЕГДА работает. Нажатие = «покажи
                    // мне сегодняшние пары»: сбрасывает неделю на текущую
                    // и включает фильтр «Сегодня».
                    //
                    // Раньше здесь стояла защита if (isCurrentWeek), и на
                    // сдвинутой неделе кнопка была МЁРТВОЙ: нажимаешь — ничего.
                    // Отсюда претензия. Теперь она не мёртвая, а ведёт домой:
                    // одно нажатие вместо двух («сейчас», потом «Сегодня»).
                    onClick = { weekOffset = 0; showWeek = false },
                    label = {
                        Text(
                            when {
                                !isCurrentWeek -> "Сегодня: ${DAY_SHORT[todayDay]}"
                                todayDay == 0 -> "Вс (выходной)"
                                else -> "Сегодня: ${DAY_SHORT[todayDay]} ${dateShort(todayDay, weekOffset, semestrStartIso)}"
                            },
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                )
                FilterChip(
                    modifier = Modifier.width(120.dp),
                    selected = showWeek,
                    onClick = { showWeek = true },
                    label = { Text("Вся неделя", fontSize = 13.sp, maxLines = 1) }
                )
            }

            if (error != null) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                     colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Text(error!!, Modifier.padding(12.dp), color = Color(0xFFC62828), fontSize = 13.sp)
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                weekLessons.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Расписание не найдено для $group", color = Color.Gray, fontSize = 14.sp)
                }
                !showWeek && todayLessons.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when {
                                    !isCurrentWeek -> "${WeekType.dayWithDate(todayDay, weekOffset, semestrStartIso)} пар нет"
                                    todayDay == 0 -> "Воскресенье — выходной"
                                    else -> "Сегодня ${WeekType.dayWithDate(todayDay, weekOffset, semestrStartIso).lowercase()} пар нет"
                                },
                                fontSize = 16.sp, color = Color.Gray
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                WeekType.name(activeWeekRow),
                                fontSize = 13.sp, color = MIET_BLUE
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Переключи на «Вся неделя», чтобы посмотреть всё",
                                fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
                else -> {
                    val shown = if (showWeek) byDay else mapOf(todayDay to todayLessons)
                    val count = shown.values.sumOf { it.size }
                    Text(
                        // Форму числительного: 1 пара, 2 пары, 5 пар. Раньше стояло
                        // жёсткое «пар», и на экране аудитории читалось «1 пар».
                        if (showWeek) "$count ${plural(count, "пара", "пары", "пар")} • ${WeekType.name(activeWeekRow)}"
                        else "$count ${plural(count, "пара", "пары", "пар")}" +
                            (if (isCurrentWeek) " сегодня" else "") +
                            " • ${WeekType.name(activeWeekRow)}",
                        fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        // П.2. Дни БЕЗ ПАР пропускаем целиком — вместе с их
                        // заголовком. Раньше здесь рисовался заголовок дня
                        // «пятница 02.10.2026» даже там, где пар нет, и на
                        // сдвинутой неделе он оставался в синей рамке с
                        // подписью «— сегодня»: то есть приложение указывало
                        // на сегодняшний день, в котором уже ничего нет.
                        shown.filter { (day, ls) -> day == markedDay || ls.isNotEmpty() }
                            .forEach { (day, dayLessons) ->
                            item(key = "hdr$day") {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (day == markedDay) MIET_BLUE else Color(0xFFE3F2FD))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            // day уже в нашей шкале 0..6 (0=Пн),
                                            // см. groupBy { (it.day ?: 1) - 1 } выше.
                                            // С датой: «пятница 02.10.2026». Без неё
                                            // карточки следующей недели выглядят
                                            // как сегодняшние — при листании «›».
                                            WeekType.dayWithDate(day, weekOffset, semestrStartIso),
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                            color = if (day == markedDay) Color.White else Color(0xFF1A1A1A)
                                        )
                                    }
                                    if (day == markedDay) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("— сегодня", fontSize = 11.sp, color = MIET_BLUE)
                                    }
                                }
                            }
                            items(
                                dayLessons.sortedBy { it.time?.code ?: 0 },
                                // Ключ обязан быть уникален внутри списка. У аудитории
                                // в одном слоте (день+неделя+пара) сидят НЕСКОЛЬКО
                                // групп — поэтому group в ключе обязателен, иначе
                                // LazyColumn падает с "Key was already used".
                                key = {
                                    "${it.day}_${it.time?.code}_${it.classInfo?.code}_" +
                                        "${it.dayNumber}_${it.group?.name.orEmpty()}_${it.room?.name.orEmpty()}"
                                }
                            ) { lesson ->
                                LessonCard(lesson, times, role)
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Карточка пары. Содержимое зависит от роли: студенту важны преподаватель и
 * аудитория, аудитории — кто придёт и из какой группы, преподавателю — группы.
 * Показывать всё сразу значит забить карточку лишней строкой.
 */
@Composable
fun LessonCard(lesson: Lesson, times: List<PairTime>, role: Role) {
    val ci = lesson.classInfo
    val pairNo = lesson.time?.code ?: 0

    val pairTime = times.firstOrNull { it.code == pairNo }
    val timeLabel = pairTime?.let { p ->
        val from = p.timeFrom?.substringAfter("T")?.take(5)?.takeIf { it.isNotBlank() && it != "00:00" }
        val to = p.timeTo?.substringAfter("T")?.take(5)?.takeIf { it.isNotBlank() && it != "00:00" }
        if (from != null && to != null) "$from–$to" else (p.time ?: "")
    } ?: ""

    val isLab = ci?.form == true

    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(10.dp)) {
            // П.4: номер пары и время — в одной колонке фиксированной ширины,
            // обе строки центрируются ПО ВИДИМОМУ ТЕКСТУ.
            //
            // Ширина 66.dp была меньше, чем «09:00–10:20» при 10.sp, и
            // строка переносилась/уезжала вправо. Считаем ширину по самой
            // длинной подписи и центрируем явно.
            val timeWidth = if (timeLabel.isNotBlank()) 84.dp else 40.dp
            Column(
                Modifier.width(timeWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$pairNo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                if (timeLabel.isNotBlank()) {
                    Text(
                        timeLabel,
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(ci?.name ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        when (role) {
                            Role.STUDENT -> {
                                if (!ci?.teacherFull.isNullOrBlank()) append(ci?.teacherFull)
                                if (!lesson.room?.name.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("ауд. ${lesson.room?.name}")
                                }
                            }
                            Role.AUDIENCE -> {
                                // Своя аудитория неинформативна — показываем,
                                // кто в неё придёт и из какой группы.
                                val g = lesson.group?.name
                                if (!g.isNullOrBlank()) append(g)
                                if (!ci?.teacherFull.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append(ci?.teacherFull)
                                }
                            }
                            Role.TEACHER -> {
                                val g = lesson.group?.name
                                if (!g.isNullOrBlank()) append(g)
                                if (!lesson.room?.name.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("ауд. ${lesson.room?.name}")
                                }
                            }
                        }
                    },
                    fontSize = 12.sp, color = Color(0xFF555555)
                )
                if (isLab) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.background(Color(0xFFFFF3E0), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("ЛАБОРАТОРНАЯ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    }
                }
            }
        }
    }
}
