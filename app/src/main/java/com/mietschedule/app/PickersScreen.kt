package com.mietschedule.app

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Выбор преподавателя: инициал → ФИО, с поиском и звездами. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherPickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    onChosen: (name: String, code: String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onAbout: () -> Unit = {},
    refreshing: Boolean = false,
    // Прогресс обновления под шапкой: обновляются все 344 группы, это
    // ~35 секунд, и без текста ожидание выглядит как зависшее приложение.
    refreshNote: String = ""
) {
    var teachers by remember { mutableStateOf<List<TeacherIndex.Teacher>>(emptyList()) }
    var building by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openLetter by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(prefs.favGroups(Role.TEACHER).toSet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { TeacherIndex.cached(api) }
        if (cached != null) {
            teachers = cached
        } else {
            // Список групп нужен и для индекса. Если его нет (первый запуск,
            // или пользователь сразу выбрал роль «преподаватель»), подгружаем
            // здесь — заставлять его сначала зайти в режим студента нечестно.
            var groups = withContext(Dispatchers.IO) { api.cachedGroups() }
            if (groups.isEmpty()) {
                // ВАЖНО: сетевой вызов обязан идти на Dispatchers.IO. Вызов прямо
                // из тела LaunchedEffect идёт на главном потоке и падает с
                // NetworkOnMainThreadException.
                groups = try { withContext(Dispatchers.IO) { api.fetchGroups() } }
                catch (e: Exception) {
                    Log.w("TeacherPicker", "Список групп не получен", e)
                    emptyList()
                }
            }
            if (groups.isEmpty()) {
                error = "Нужен список групп, а он не загрузился: ${error ?: "нет сети"}"
                return@LaunchedEffect
            }
            building = true
            total = groups.size
            runCatching {
                TeacherIndex.build(api, groups) { d, t ->
                    done = d; progress = if (t > 0) d.toFloat() / t else 0f
                }
            }.onSuccess { teachers = it }
                .onFailure {
                    Log.w("TeacherPicker", "Индекс не собрался", it)
                    error = "Не получилось собрать список: ${it.message}"
                }
            building = false
        }
    }

    // Служебные каналы прячем из общего списка: у них тысячи пар, это не человек.
    val visible = remember(teachers, query) {
        val q = query.trim()
        teachers.filter { !TeacherIndex.isService(it.name) }
            .filter {
                q.isEmpty() ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.short.contains(q, ignoreCase = true)
            }
    }
    val byLetter = remember(visible) {
        visible.groupBy { it.name.take(1).uppercase() }.toSortedMap()
    }

    Scaffold(
        topBar = {
            // Общая шапка MietTopBar. Размер иконки обновления здесь раньше был
            // 24.dp, на остальных экранах 22.dp — при одинаковой кнопке это
            // давало разную ширину и, следовательно, разный слот.
            MietTopBar(
                title = "Преподаватель",
                subtitle = if (teachers.isEmpty()) "Загрузка…"
                else "${byLetter.values.sumOf { it.size }} преподавателей",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Прогресс обновления: обновляются все 344 группы — это ~35 секунд.
            // Без этой строки ожидание выглядит как зависшее приложение.
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    fontSize = 12.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; openLetter = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск: Лупин, Лупин С.С.…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            when {
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = LocalAppColors.current.error, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                building -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Собираю расписания преподавателей",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Собираю из расписаний всех групп. Один раз, потом из кэша.",
                        fontSize = 12.sp, color = LocalAppColors.current.muted
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Групп обработано: $done из $total", fontSize = 12.sp, color = LocalAppColors.current.muted)
                }
                teachers.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    if (favs.isNotEmpty() && query.isBlank()) {
                        FavStrip("★ Избранные преподаватели", favs.toList()) { name ->
                            val t = teachers.firstOrNull { it.name == name }
                            if (t != null) onChosen(t.name, t.code)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        byLetter.forEach { (letter, list) ->
                            item(key = "l_$letter") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                openLetter = if (openLetter == letter) null else letter
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            letter, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                            color = MIET_BLUE, modifier = Modifier.width(26.dp)
                                        )
                                        Text(
                                            "${list.size}", fontSize = 13.sp, color = LocalAppColors.current.muted,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (openLetter == letter) "\u2212" else "+",
                                            fontSize = 20.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (openLetter == letter) {
                                items(list, key = { it.code }) { t ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onChosen(t.name, t.code) }
                                            .padding(start = 40.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(t.name, fontSize = 13.sp)
                                            Text(
                                                "${t.short} • ${t.pairCount} пар",
                                                fontSize = 11.sp, color = LocalAppColors.current.muted
                                            )
                                        }
                                        IconButton(onClick = {
                                            favs = if (prefs.toggleFavFor(Role.TEACHER, t.name)) {
                                                favs + t.name
                                            } else {
                                                favs - t.name
                                            }
                                        }) {
                                            Icon(
                                                if (t.name in favs) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "В избранное",
                                                tint = if (t.name in favs) LocalAppColors.current.favStar else LocalAppColors.current.starInactive,
                                                modifier = Modifier.size(19.dp)
                                            )
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

/** Выбор аудитории: корпус → аудитория. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    onChosen: (code: Int, name: String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onAbout: () -> Unit = {},
    refreshing: Boolean = false,
    // Прогресс обновления под шапкой: обновляются все 344 группы, это
    // ~35 секунд, и без текста ожидание выглядит как зависшее приложение.
    refreshNote: String = ""
) {
    var list by remember { mutableStateOf<List<Audience>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openBldg by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(prefs.favGroups(Role.AUDIENCE).toSet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Сначала то, что сервер знает, — это быстро.
        // Кэш пуст после первой установки — тогда идём сразу в сеть.
        val fromServer = withContext(Dispatchers.IO) {
            val c = api.cachedAudiences()
            if (c.isNotEmpty()) c else runCatching { api.fetchAudiences() }.getOrDefault(emptyList())
        }
        if (fromServer.isNotEmpty()) {
            list = fromServer; loading = false
        }
        // Потом дополняем аудиториями из расписаний: сайт отдаёт 136, а в паре
        // встречается 194 — без этого корпус 8 (8102, 8103, …) вообще не виден.
        //
        // Считать есть только что из кэша. Но если пользователь ни разу не открывал
        // расписание группы, кэша расписаний ещё нет — тогда сначала забираем
        // список групп: их 344, один запрос, зато аудитории появятся сразу.
        val extra = withContext(Dispatchers.IO) {
            if (api.cachedSchedule(api.cachedGroups().firstOrNull() ?: "") == null) {
                api.deriveAudiencesFromGroups(fetchGroupsIfNeeded = true)
            } else {
                api.deriveAudiencesFromGroups()
            }
        }
        if (extra.isNotEmpty()) {
            // Склейка по ключу ПОМЕЩЕНИЯ (номер + суффикс), а не по коду.
            // Коды у двух источников не совпадают: в эндпоинте 1202 — это код 121,
            // в расписаниях тот же номер аудитории — код 1202. По коду они разные,
            // и в списке появлялись дубли «8307», «8308», «8309», «4109»,
            // «3304», «3305». По ключу это одно и то же помещение.
            val byKey = LinkedHashMap<String, Audience>()
            for (a in list) {
                if (a.name.isNullOrBlank()) continue
                byKey[roomKey(a.name)] = a
            }
            for (a in extra) {
                if (a.name.isNullOrBlank() || a.code == null) continue
                val k = roomKey(a.name)
                val existing = byKey[k]
                // Если такое помещение уже есть, оставляем то, у которого больше
                // пар в расписаниях: у серверного кода имена короче («8309»),
                // у расписного — точнее («8309 к»), и расписание по нему богаче.
                if (existing == null) {
                    byKey[k] = a
                }
            }
            list = byKey.values.sortedWith(
                compareBy(
                    { roomSortKey(it.name).first },
                    { roomSortKey(it.name).second },
                    { roomSortKey(it.name).third }
                )
            )
        }
        if (list.isEmpty() && error == null) {
            error = "Не удалось загрузить аудитории: проверь интернет"
        }
        // Флаг снимаем в конце в любом случае. Раньше он сбрасывался только
        // внутри onSuccess, и если список приходил из локального индекса, экран
        // навсегда оставался с надписью «Загрузка…» при полном списке из 194.
        loading = false
    }

    val byBldg = remember(list, query) {
        val q = query.trim()
        val filtered = list.filter { q.isEmpty() || it.name?.contains(q, ignoreCase = true) == true }
        filtered.groupBy { buildingOf(it.name) }
            // Корпуса — по номеру (1, 3, 4, … 8), служебные («ДК МИЭТ», «УВЦ»,
            // «Виртуальные аудитории», «Аудитории практики», «Прочее») — после них.
            // Раньше toSortedMap() ставил «Аудитории практики» перед «Корпус 1».
            .toList()
            .sortedWith(
                compareBy({ it.first.startsWith("Корпус ") },
                          { it.first.removePrefix("Корпус ").toIntOrNull() ?: Int.MAX_VALUE },
                          { it.first })
            )
            .associate { (bldg, items) -> bldg to items.sortedWith(
                compareBy(
                    { roomSortKey(it.name).first },
                    { roomSortKey(it.name).second },
                    { roomSortKey(it.name).third }
                )
            ) }
    }

    Scaffold(
        topBar = {
            // Та же общая шапка, что и у преподавателя и студента: одинаковый
            // размер иконки обновления (22.dp, не 24.dp) и одинаковый набор
            // соседей, поэтому кнопка занимает один и тот же слот.
            MietTopBar(
                title = "Аудитория",
                subtitle = if (list.isEmpty()) "Загрузка…"
                else "${list.size} " + plural(list.size, "аудитория", "аудитории", "аудиторий"),
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Прогресс обновления: обновляются все 344 группы — это ~35 секунд.
            // Без этой строки ожидание выглядит как зависшее приложение.
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    fontSize = 12.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; openBldg = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск: 8109, 1201 м…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            when {
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = LocalAppColors.current.error, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    if (favs.isNotEmpty() && query.isBlank()) {
                        FavStrip("★ Избранные аудитории", favs.toList()) { name ->
                            val a = list.firstOrNull { it.name == name }
                            if (a?.code != null) onChosen(a.code, name)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        byBldg.forEach { (bldg, items_) ->
                            item(key = "b_$bldg") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                openBldg = if (openBldg == bldg) null else bldg
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(bldg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            Text("${items_.size} " + plural(items_.size, "аудитория", "аудитории", "аудиторий"),
                     fontSize = 11.sp, color = LocalAppColors.current.muted)
                                        }
                                        Text(
                                            if (openBldg == bldg) "\u2212" else "+",
                                            fontSize = 22.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (openBldg == bldg) {
                                // Ключ — не it.code: код из /audiences оказался внутренним id, и
                                // разные помещения его делят («8307» и «8307 к» — оба
                                // id 234). LazyColumn падал на «Key was already used».
                                // Имя уникально после склейки, поэтому ключ строим от него.
                                items(items_, key = { "a_${roomKey(it.name)}_${it.code}" }) { a ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { if (a.code != null) onChosen(a.code, a.name.orEmpty()) }
                                            .padding(start = 14.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(a.name.orEmpty(), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        IconButton(onClick = {
                                            val n = a.name.orEmpty()
                                            favs = if (prefs.toggleFavFor(Role.AUDIENCE, n)) favs + n else favs - n
                                        }) {
                                            Icon(
                                                if (a.name.orEmpty() in favs) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "В избранное",
                                                tint = if (a.name.orEmpty() in favs) LocalAppColors.current.favStar else LocalAppColors.current.starInactive,
                                                modifier = Modifier.size(19.dp)
                                            )
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
 * Ключ помещения для склейки двух списков аудиторий.
 *
 * Приложение берёт аудитории из двух источников: эндпоинт /audiences (136 штук)
 * и расписания групп (194 пары «код, имя»). Одни и те же помещения названы
 * по-разному и имеют РАЗНЫЕ коды, поэтому склеивать по коду нельзя:
 *
 *   код 121 «1202 (м)» в эндпоинте  ↔  код 1202 «1202» в расписаниях
 *   код 97  «8309»     в эндпоинте  ↔  код 8309 «8309» в расписаниях
 *   код 144 «3304»     в эндпоинте  ↔  код 3304 «3304» в расписаниях
 *
 * При склейке по коду такие пары не вылавливались, и в списке корпуса 8
 * «8307», «8308» и «8309» показывались по два раза.
 *
 * Ключ — номер аудитории плюс существенный суффикс. Проверено на данных:
 *   - «8307» (код 8307) и «8307 к» (код 234) — РАЗНЫЕ аудитории, «к» важен;
 *   - «3303 а» (код 143) и «3303 м» (код 135) — РАЗНЫЕ, буква значима;
 *   - буквы «м» и «л» — просто уточнение, помещение не различают;
 *   - скобки ВСЕГДА игнорируются: код 98 — это «3105 (к)» в эндпоинте и «3105»
 *     в расписаниях, то есть одна и та же аудитория;
 *   - «3350амЛПО» и «3350бм ЛПО» — РАЗНЫЕ, суффикс берём целиком.
 *
 * Помещения без номера (УВЦ 1, ДК МИЭТ, Виртуальная аудитория 9,
 * Аудитория практической подготовки 7) ключуются по имени.
 */
internal fun roomKey(name: String?): String {
    val s = (name ?: "").trim()
    val digits = Regex("^(\\d{3,4})").find(s) ?: return "name:" + s.lowercase()
    val num = digits.groupValues[1].toInt()
    val rest = s.substring(digits.value.length).trim()
    val outsideParens = Regex("^\\s*[а-яА-Яа-я]+").find(rest)?.value?.trim()?.lowercase() ?: ""
    val essential = if (outsideParens == "м" || outsideParens == "л") "" else outsideParens
    return "num:$num:$essential"
}

/**
 * Порядок аудиторий внутри корпуса: по номеру, а не по строке.
 *
 * Сортировка строками даёт неудобный порядок: «8107» встал бы после «81010»,
 * а «3205 МПСУ» смешалось бы с «3207 м» по буквам. Разбираем номер числом,
 * потом суффикс, потом уже текстовую часть (для УВЦ и прочих без номера).
 */
internal fun roomSortKey(name: String?): Triple<Int, String, String> {
    val s = (name ?: "").trim()
    val digits = Regex("^(\\d{3,4})").find(s)
        ?: return Triple(Int.MAX_VALUE, "", s.lowercase())
    val num = digits.groupValues[1].toInt()
    val rest = s.substring(digits.value.length).trim()
    // Тот же суффикс, что и в roomKey: буква сразу после номера без скобок.
    val outsideParens = Regex("^\\s*[а-яА-Яа-я]+").find(rest)?.value?.trim()?.lowercase() ?: ""
    val essential = if (outsideParens == "м" || outsideParens == "л") "" else outsideParens
    // Третья компонента — чтобы «8307» шёл раньше «8307 к», а при равном
    // суффиксе порядок всё равно был стабильным.
    return Triple(num, essential, s.lowercase())
}

fun buildingOf(name: String?): String {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return "Прочее"
    val first = n.firstOrNull()
    if (first != null && first.isDigit()) return "Корпус $first"
    // Без цифры: группируем по первому слову, но «ДК МИЭТ» — это одно название,
    // резать его на «ДК» бессмысленно.
    return when {
        n.startsWith("ДК") -> "ДК МИЭТ"
        n.startsWith("УВЦ") -> "УВЦ"
        n.startsWith("Виртуальная") -> "Виртуальные аудитории"
        n.startsWith("Аудитория") -> "Аудитории практики"
        else -> n.substringBefore(' ').ifEmpty { "Прочее" }
    }
}



/** Плашка избранного в одну строку, общая для трёх экранов выбора. */
@Composable
fun FavStrip(title: String, items: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.favStar)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it }) { g ->
                Card(
                    Modifier.clickable { onPick(g) },
                    colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.fav)
                ) {
                    Text(
                        g, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}