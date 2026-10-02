package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

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
internal fun dayIndexFromCalendar(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = MIET_BLUE)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F7FA)) {
                    AppRoot(requestedGroup = intent?.getStringExtra(EXTRA_GROUP))
                // Проверка новой версии при запуске. Идёт в фоне: открытие
                // приложения не ждёт сеть, диалог всплывёт позже, если есть.
                UpdatePromptHost(currentVersionCode = BuildConfig.VERSION_CODE)
                }
            }
        }
    }
}

/**
 * Куда пользователь по выбору роли: сначала роль, потом своя сущность
 * (группа / преподаватель / аудитория), и только потом расписание.
 */
private enum class Screen { PICK_ROLE, PICK_ENTITY, SCHEDULE, FAVORITES, ABOUT }

@Composable
fun AppRoot(requestedGroup: String? = null) {
    val context = LocalContext.current
    val api = remember { MietApi(context) }
    val prefs = remember { GroupPrefs(context) }

    // Роль живёт в хранилище: переживает перезапуск, иначе пришлось бы
    // спрашивать её при каждом запуске.
    var role by remember { mutableStateOf(prefs.role()) }
    // Выбор в рамках роли. Для преподавателя храним ФИО, для аудитории — имя
    // (по имени проще искать в избранном), код аудитории добираем из списка.
    var teacherCode by remember { mutableStateOf("") }
    var roomNameArg by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(requestedGroup ?: prefs.load()) }

    var screen by remember {
        mutableStateOf(if (prefs.load() != null || requestedGroup != null) Screen.PICK_ENTITY else Screen.PICK_ROLE)
    }
    // Кнопка «Обновить» есть на всех трёх экранах выбора, поэтому состояние
    // живёт здесь и передаётся вниз — иначе каждый экран вёл бы свой счётчик.
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * Принудительное обновление данных под текущую роль.
     *
     * Для преподавателя это пересборка индекса (обход 344 групп), поэтому он
     * самый долгий; для студента и аудитории — точечное обновление. Ход работы
     * на IO-диспетчере: сетевой вызов из главного потока даёт
     * NetworkOnMainThreadException.
     */
    fun refreshCurrent() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            withContext(Dispatchers.IO) {
                runCatching {
                    when (role) {
                        Role.STUDENT -> selection?.let { api.fetchSchedule(it) }
                        Role.AUDIENCE -> {
                            api.fetchGroups()
                            api.fetchAudiences()
                        }
                        Role.TEACHER -> {
                            val groups = api.fetchGroups()
                            TeacherIndex.build(api, groups)
                        }
                    }
                }.onFailure { Log.w("Refresh", "Не обновилось: ${it.message}") }
            }
            refreshing = false
        }
    }

    when (screen) {
        Screen.PICK_ROLE -> RolePickerScreen(
            current = if (prefs.load() != null) role else null,
            onRefresh = { refreshCurrent() },
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
                    onSwitchRole = { screen = Screen.PICK_ROLE }
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
                    onBack = { screen = Screen.PICK_ROLE }
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
                    onAbout = { screen = Screen.ABOUT },
                    refreshing = refreshing
                )
            }
        }

        Screen.ABOUT -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onBack = { screen = if (selection != null) Screen.PICK_ENTITY else Screen.PICK_ROLE }
        )

        Screen.FAVORITES -> FavoritesScreen(
            prefs = prefs,
            onOpen = { r, value ->
                role = r
                selection = value
                teacherCode = ""
                screen = Screen.PICK_ENTITY
            },
            onBack = { screen = Screen.SCHEDULE }
        )

        Screen.SCHEDULE -> ScheduleScreen(
            api = api,
            prefs = prefs,
            role = role,
            group = selection!!,
            teacherCode = teacherCode,
            roomName = roomNameArg,
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

// ───────────────────── выбор группы: направление → группа ─────────────────────


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    currentGroup: String?,
    onChosen: (String) -> Unit,
    onSwitchRole: () -> Unit = {}
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
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MIET_BLUE),
                title = {
                    Column {
                        Text("Расписание МИЭТ", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Выбери свою группу", color = Color(0xFFBBDEFB), fontSize = 12.sp)
                    }
                },
                actions = {
                    // Возврат к выбору роли: без него из экрана группы можно было
                    // выйти только кнопкой «назад», которая закрывает приложение.
                    TextButton(onClick = onSwitchRole) {
                        Text("Сменить роль", color = Color.White, fontSize = 13.sp)
                    }
                }
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
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    onOpenFavorites: () -> Unit = {}
) {
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var times by remember { mutableStateOf<List<PairTime>>(emptyList()) }
    var semestr by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showWeek by remember { mutableStateOf(false) }
    var weekName by remember { mutableStateOf(WeekType.currentName()) }
    var weekRow by remember { mutableStateOf(WeekType.currentRowIndex()) }

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
    val todayDay = remember { dayIndexFromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }
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
        weekName = WeekType.currentName(ss)
        weekRow = WeekType.currentRowIndex(ss)
    }

    fun apply(raw: String) {
        val resp = GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)
        lessons = resp.data ?: emptyList()
        // Таблица времени: серверная, но с подстраховкой от самих пар — у части
        // аудиторий сервер отдаёт пустой Times, и карточка осталась бы без времени.
        times = mergeTimes(resp.times, lessons)
        semestr = resp.semestr ?: ""
    }

    fun load(force: Boolean) {
        scope.launch {
            refreshing = true
            error = null
            when (role) {
                Role.STUDENT -> {
                    val cached = withContext(Dispatchers.IO) { api.cachedSchedule(group) }
                    if (cached != null) runCatching { apply(cached) }
                    if (force || lessons.isEmpty()) {
                        runCatching { withContext(Dispatchers.IO) { api.fetchSchedule(group) } }
                            .onSuccess { raw -> runCatching { apply(raw) } }
                            .onFailure {
                                Log.w("Schedule", "Не получили $group", it)
                                if (lessons.isEmpty()) error = "Не удалось загрузить расписание: ${it.message}"
                            }
                    }
                }

                Role.AUDIENCE -> {
                    // В расписании аудитории сервер отдаёт сетку занятости:
                    // в каждой паре Class = кто придёт, Group = чья это группа.
                    val code = teacherCode.toIntOrNull()
                    if (code == null) {
                        error = "Не удалось определить аудиторию"
                    } else {
                        // Кэш аудитории берём ТОЛЬКО если он непустой. Старые
                        // сборки клали туда ответ сервера «Data: []», и такой
                        // кэш годами выглядит как «расписание есть, но пустое»:
                        // apply() подставляет его, lessons непустой, и сборка из
                        // кэша расписаний групп не запускается. Именно так у
                        // 8307 показывалось 12 чужих пар вместо 72 своих.
                        val cached = withContext(Dispatchers.IO) { api.cachedAudience(code) }
                        if (cached != null) {
                            val parsed = runCatching {
                                val r = GsonHolder.gson.fromJson(cached, ScheduleResponse::class.java)
                                !r.data.isNullOrEmpty()
                            }.getOrDefault(false)
                            if (parsed) runCatching { apply(cached) }
                        }

                        if (force || lessons.isEmpty()) {
                            val got = runCatching {
                                withContext(Dispatchers.IO) { api.fetchAudience(code) }
                            }.onSuccess { raw -> runCatching { apply(raw) } }
                                .onFailure {
                                    Log.w("Schedule", "Сервер не отдал аудиторию $code", it)
                                }.isSuccess

                            // Сервер знает только 136 из 221 аудитории. Для остальных
                            // (все 17 корпуса 8, корпус 6, УВЦ, виртуальные и
                            // аудитории практики) он отдаёт пустой ответ — тогда
                            // собираем занятость из кэша расписаний групп ПО ИМЕНИ.
                            // Условие — именно lessons.isEmpty(), а не «!got»:
                            // пустой успешный ответ сервера тоже должен вести
                            // к локальной сборке, а не останавливаться на нём.
                            if (!got || lessons.isEmpty()) {
                                val groups = withContext(Dispatchers.IO) { api.cachedGroups() }
                                if (groups.isEmpty()) {
                                    withContext(Dispatchers.IO) { api.fetchGroups() }
                                }
                                val local = withContext(Dispatchers.IO) {
                                    api.localLessonsOf(
                                        api.cachedGroups(), code,
                                        roomName.ifEmpty { group }
                                    )
                                }
                                Log.i("Schedule", "Аудитория $code: локально ${local.size} пар")
                                // Локальная сборка богаче ответа сервера — берём её.
                                // Для аудиторий вроде 8307 сервер знает 12 пар (это
                                // соседнее «8307 к»), а в расписаниях групп её 72.
                                if (local.size > lessons.size) {
                                    lessons = local
                                    // Аудитории, которых нет на сайте, собираются
                                    // из кэша расписаний групп: серверной таблицы
                                    // Times для них нет, время берём из самих пар.
                                    times = mergeTimes(null, local)
                                    semestr = "Осенний семестр"
                                    error = null
                                } else if (lessons.isEmpty()) {
                                    error = "Расписание не найдено для $code"
                                }
                            }
                        }
                    }
                }

                Role.TEACHER -> {
                    // Расписания преподавателя сервер не отдаёт: собираем из
                    // кэша расписаний групп. Если кэша нет — сначала строим
                    // индекс (это один проход по всем группам).
                    var groups = withContext(Dispatchers.IO) { api.cachedGroups() }
                    if (groups.isEmpty()) {
                        // Сетевой вызов — только на Dispatchers.IO, иначе
                        // NetworkOnMainThreadException.
                        groups = try { withContext(Dispatchers.IO) { api.fetchGroups() } }
                        catch (e: Exception) {
                            Log.w("Schedule", "Список групп не получен", e)
                            emptyList()
                        }
                    }
                    if (groups.isEmpty()) {
                        loading = false; refreshing = false
                        error = "Список групп не загрузился — проверь интернет"
                        return@launch
                    }
                    var found = withContext(Dispatchers.IO) {
                        TeacherIndex.lessonsOf(api, groups, teacherCode)
                    }
                    if (found.isEmpty()) {
                        // Индекс мог устареть: преподаватель взял новую группу.
                        withContext(Dispatchers.IO) { TeacherIndex.build(api, groups) }
                        found = withContext(Dispatchers.IO) {
                            TeacherIndex.lessonsOf(api, groups, teacherCode)
                        }
                    }
                    lessons = found
                    // Расписание преподавателя собирается из кэша расписаний
                    // групп, где верхней таблицы Times нет. Без этого карточка
                    // показывала номер пары, но не время начала и конца.
                    times = mergeTimes(null, found)
                    semestr = "Осенний семестр"
                    if (found.isEmpty()) error = "Пар не найдено — проверь ФИО"
                }
            }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(group, teacherCode, role) { load(false) }

    // Только строка текущей учебной недели (DayNumber == weekType).
    // Фильтр по чётности тут давал бы дубли: строки 0 и 2 у части групп
    // содержат одинаковые пары, а у ИВТ-11/ПИН-11 — разные.
    val weekLessons = remember(lessons, weekRow) {
        lessons.filter { (it.dayNumber ?: 0) == weekRow }
    }
    val byDay = weekLessons.groupBy { it.day ?: 1 }.toSortedMap()
    val todayLessons = byDay[todayDay].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MIET_BLUE),
                title = {
                    Column {
                        Text(group, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            buildString {
                                append(role.title.removePrefix("Я "))
                                append(" • ")
                                append(if (semestr.isBlank()) "Загрузка…" else semestr)
                                append(" • $weekName")
                            },
                            color = Color(0xFFBBDEFB), fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { prefs.toggleFavFor(role, group); refreshFav() }) {
                        Text(
                            if (isFav) "★" else "☆",
                            color = if (isFav) Color(0xFFFFD54F) else Color.White,
                            fontSize = 21.sp
                        )
                    }
                    // Список избранного: раньше до него надо было доходить через
                    // экран выбора, теперь он открывается отсюда.
                    IconButton(onClick = onOpenFavorites) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = "Избранное",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    TextButton(onClick = { onChangeEntity() }) {
                        Text("Сменить", color = Color.White, fontSize = 13.sp)
                    }
                    TextButton(onClick = { onChangeRole() }) {
                        Text("Роль", color = Color(0xFFBBDEFB), fontSize = 13.sp)
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showWeek,
                    onClick = { showWeek = false },
                    label = { Text(if (todayDay == 0) "Вс (выходной)" else "Сегодня: ${DAY_SHORT[todayDay]}", fontSize = 13.sp) }
                )
                FilterChip(
                    selected = showWeek,
                    onClick = { showWeek = true },
                    label = { Text("Вся неделя", fontSize = 13.sp) }
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { load(true) }, enabled = !refreshing) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("↻", fontSize = 20.sp, color = MIET_BLUE)
                }
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
                                if (todayDay == 0) "Воскресенье — выходной"
                                else "Сегодня ${DAY_NAMES[todayDay].lowercase()} пар нет",
                                fontSize = 16.sp, color = Color.Gray
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("$weekName", fontSize = 13.sp, color = MIET_BLUE)
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
                        if (showWeek) "$count пар • $weekName" else "$count пар сегодня • $weekName",
                        fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        shown.forEach { (day, dayLessons) ->
                            item(key = "hdr$day") {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (day == todayDay) MIET_BLUE else Color(0xFFE3F2FD))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            DAY_NAMES.getOrElse(day - 1) { "День $day" },
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                            color = if (day == todayDay) Color.White else Color(0xFF1A1A1A)
                                        )
                                    }
                                    if (day == todayDay) {
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
            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$pairNo", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MIET_BLUE)
                if (timeLabel.isNotBlank()) {
                    Text(timeLabel, fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center)
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
