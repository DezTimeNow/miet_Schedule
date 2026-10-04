package com.mietschedule.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * ЭКРАН «АУДИТОРИИ» — кто где сейчас, и что свободно.
 *
 * Отдельный экран, а не раздел экрана аудитории: список корпусов и фильтры
 * занимают больше места, чем помещается в карточку, а роль «Аудитория»
 * показывает расписание одной комнаты, а не всех.
 *
 * Данные — из кэша расписаний всех групп, а не из /audiences: в эндпоинте
 * 136 комнат из 194, и корпус 8 там отсутствует целиком (см. [MietApi]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    api: MietApi,
    onOpenRoom: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = "",
/**
     * Поколение данных — то же самое, что у экрана расписания.
     *
     * Без него список аудиторий читался из кэша ОДИН раз, при входе на
     * экран: нажатие «Обновить всё» писало в кэш 343 расписания, а список
     * продолжал показывать «пусто» — то есть обновление на этом экране
     * было враньём. Ключ LaunchedEffect ниже — на этом поколении.
     */
    dataGeneration: Int = 0,
) {
    // Состояние фильтров переживает поворот: иначе список фильтров и
    // выбранный корпус возвращались к значениям по умолчанию.
    var showFree by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var building by rememberSaveable { mutableStateOf("") }
    var pairNo by rememberSaveable { mutableStateOf("") }

    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<Audience>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Номер пары для фильтра занятости: по умолчанию текущая по времени.
    // Берём из таблицы времени ближайшей пары, а не из номера: у разных
    // групп вторая пара начинается в разное время.
    val todayDay = remember { dayIndexFromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }

    // Текущая учебная неделя. Занятость считается по ней, а не по всем четырём:
    // см. пояснение у busyKeys ниже. Читаем из кэша семестра, как на экране
    // расписания, чтобы обе точки смотрели на одну и ту же неделю.
    val weekRow = remember {
        runCatching { WeekType.currentRowIndex() }.getOrDefault(0)
    }

    // Ключ — поколение данных. 0 при первом входе, дальше растёт при каждом
    // «Обновить всё».
    LaunchedEffect(dataGeneration) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // ГЛАВНЫЙ ИСТОЧНИК — расписания групп. Один проход по кэшу
                // даёт и список аудиторий, и занятость.
                //
                // Раньше список брался из кэша /audiences (136 комнат), а
                // корпус 8 — из localAudiences(), который заполняется только
                // на экране выбора аудитории. На этом экране оба пусты при
                // первом входе, и человек видел «Список аудиторий пуст»,
                // хотя в 343 расписаниях было 194 комнаты.
                val groups = api.cachedGroups()
                val allLessons = mutableListOf<Lesson>()
                // Из пар собираем и имена: в эндпоинте корпуса 8 нет вовсе.
                val fromLessons = LinkedHashMap<String, Audience>()

                for (g in groups) {
                    val raw = api.cachedSchedule(g) ?: continue
                    val data = runCatching {
                        GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data
                    }.getOrNull() ?: continue
                    allLessons.addAll(data)
                    for (l in data) {
                        val name = l.room?.name?.trim().orEmpty()
                        if (name.isEmpty()) continue
                        val code = l.room?.roomCode() ?: continue
                        val k = roomKey(name)
                        if (k.isNotEmpty()) fromLessons.putIfAbsent(k, Audience(code = code, name = name))
                    }
                }

                // Склеиваем по roomKey: имя может отличаться («1201 (м)» в
                // эндпоинте и «1201» в паре — это одна комната).
                val merged = LinkedHashMap<String, Audience>()
                (fromLessons.values + api.cachedAudiences()).forEach { a ->
                    val k = roomKey(a.name)
                    if (k.isNotEmpty()) merged.putIfAbsent(k, a)
                }

                // Triple не Comparable, поэтому сортируем по трём компонентам
                // цепочкой compareBy — так же, как уже сделано на экране
                // выбора аудитории (PickersScreen). Сортировка строкой дала бы
                // неудобный порядок: «8107» после «81010».
                val allRooms = merged.values.sortedWith(
                    compareBy(
                        { roomSortKey(it.name).first },
                        { roomSortKey(it.name).second },
                        { roomSortKey(it.name).third },
                    )
                )
                allRooms to allLessons
            }
        }
        result.onSuccess { (r, l) ->
            rooms = r
            lessons = l
        }.onFailure {
            error = "Не удалось прочитать список аудиторий: ${it.message}"
        }
        loading = false
    }

    // ───────── Фильтры ─────────

    val buildings = remember(rooms) {
        rooms.map { buildingOf(it.name) }.distinct().sorted()
    }

    // Занятые комнаты считаются один раз, а не на каждый элемент списка: иначе
        // каждая строка спрашивала бы расписание заново при прокрутке.
        //
        // Фильтр по DayNumber обязателен. Без него комната считалась занятой из-за
        // пары, которой сегодня нет: DayNumber 0..3 — это конкретная УЧЕБНАЯ
        // неделя (0 → 1-й числитель, 1 → 1-й знаменатель…), и у одной группы в
        // числителе пара в 205, а в знаменателе она же в 310. Без фильтра человек
        // видел «205 занята» из-за пары не с той недели.
        val busyKeys = remember(lessons, pairNo, showFree, todayDay, weekRow) {
            if (!showFree) emptySet()
            else {
                val code = pairNo.toIntOrNull()
                lessons.asSequence()
                    .filter { (it.day ?: 1) - 1 == todayDay }
                    .filter { (it.dayNumber ?: 0) == weekRow }
                    .filter { code == null || (it.time?.code ?: -1) == code }
                    .mapNotNull { it.room?.name?.trim() }
                    .filter { it.isNotEmpty() }
                    .map { roomKey(it) }
                    .toSet()
            }
        }

    // Список после всех фильтров.
    val visible = remember(rooms, query, building, busyKeys, showFree) {
        rooms.asSequence()
            .filter { building.isEmpty() || buildingOf(it.name) == building }
            .filter { query.isBlank() || it.name?.contains(query.trim(), ignoreCase = true) == true }
            .filter { !showFree || roomKey(it.name) !in busyKeys }
            .toList()
    }

    // Группировка по корпусу — заголовок внутри списка, поэтому корпуса
    // не нужно пересобирать на каждый элемент.
    val grouped = remember(visible) { visible.groupBy { buildingOf(it.name) } }

    // В выходной и в дни без пар фильтр «свободные» даёт ВСЕ аудитории: ни одна
    // пара сегодня не стоит, значит никто ничего не занимает. Формально верно,
    // но человек читает «221 аудитория свободно» как «все комнаты пусты»,
    // и это враньё. В таких случаях говорим прямо, о чём список.
    val noLessonsToday = remember(lessons, todayDay, weekRow) {
        lessons.none {
            (it.day ?: 1) - 1 == todayDay && (it.dayNumber ?: 0) == weekRow
        }
    }

    Scaffold(
        topBar = {
            MietTopBar(
                title = "Аудитории",
                subtitle = when {
                    showFree && noLessonsToday -> "Сегодня пар нет"
                    showFree -> "Свободные сейчас"
                    else -> "Все корпуса"
                },
                onRefresh = onRefresh,
                onChangeRole = onBack,
                refreshing = refreshing,
                onBack = onBack,
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            if (refreshing && refreshNote.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    refreshNote,
                    fontSize = 11.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            // ───────── Поиск и кнопка «Показать свободные» ─────────
            //
            // Кнопка переключатель: нажатие включает фильтр занятости по
            // текущей паре, повторное — снимает. Подпись меняется, чтобы было
            // видно, в каком состоянии список.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Номер аудитории", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
                Spacer(Modifier.width(8.dp))
                // Подпись — как просил владелец: «Показать свободные».
                // Повторное нажатие возвращает полный список, и тогда на
                // кнопке написано «Показать все» — иначе непонятно, как
                // вернуть обратно.
                AssistChip(
                    onClick = { showFree = !showFree },
                    label = {
                        Text(
                            if (showFree) "Показать все" else "Свободные",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (showFree) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            Modifier.size(16.dp),
                        )
                    },
                )
            }

            // ───────── Фильтр по паре (только когда включены свободные) ─────────
            //
            // Занятость считается по номеру пары, поэтому его надо уметь
            // задать: в 12:00 и в 15:00 список свободных разный. По умолчанию
            // пусто — значит «вся пара недели», как в записи строки.
            if (showFree) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Занято на паре",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.width(96.dp),
                    )
                    val codes = remember(lessons) {
                        // Code 0 — не настоящая пара: у «Разговоров о важном»
                        // слота нет, сервер отдаёт нули. В фильтре по паре он
                        // только мешал, и по нему «занято» становилось всё
                        // подряд.
                        lessons.mapNotNull { it.time?.code }
                            .filter { it > 0 }
                            .distinct().sorted()
                    }
                    // Показываем первые шесть номеров: остальные — поиском.
                    codes.take(6).forEach { code ->
                        FilterChip(
                            modifier = Modifier.padding(end = 6.dp),
                            selected = pairNo == code.toString(),
                            onClick = {
                                pairNo = if (pairNo == code.toString()) "" else code.toString()
                            },
                            label = { Text("$code", fontSize = 12.sp) },
                        )
                    }
                    if (codes.size > 6) {
                        Text(
                            "…${codes.size}",
                            fontSize = 11.sp,
                            color = LocalAppColors.current.muted,
                        )
                    }
                }
            }

            // ───────── Фильтр по корпусу ─────────
            //
            // Корпус — чипы в одну строку с горизонтальной прокруткой:
            // список зданий короткий, а вертикальное меню занимало бы
            // пол-экрана ради одного выбора.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = building.isEmpty(),
                            onClick = { building = "" },
                            label = { Text("Все", fontSize = 12.sp) },
                        )
                    }
                    items(buildings) { b ->
                        FilterChip(
                            selected = building == b,
                            onClick = { building = if (building == b) "" else b },
                            label = { Text(b, fontSize = 12.sp) },
                        )
                    }
                }
            }

            if (error != null) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalAppColors.current.errorContainer
                    ),
                ) {
                    Text(
                        error!!,
                        Modifier.padding(12.dp),
                        color = LocalAppColors.current.error,
                        fontSize = 13.sp,
                    )
                }
            }

            // ───────── Список ─────────
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                rooms.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Список аудиторий пуст — обновите расписание",
                        color = LocalAppColors.current.muted,
                        fontSize = 14.sp,
                    )
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (showFree) "Свободных аудиторий нет" else "Ничего не найдено",
                            fontSize = 15.sp,
                            color = LocalAppColors.current.muted,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            query = ""; building = ""; pairNo = ""
                        }) {
                            Text("Сбросить фильтры", color = MIET_BLUE, fontSize = 13.sp)
                        }
                    }
                }
                else -> {
                    Text(
                        "${visible.size} ${plural(visible.size, "аудитория", "аудитории", "аудиторий")}" +
                            // Без оговорки «сегодня пар нет» надпись вводит
                            // в заблуждение: в выходной свободны все комнаты
                            // просто потому, что никто не занимает их по
                            // расписанию.
                            (if (showFree && noLessonsToday) " — сегодня пар нет" else if (showFree) " свободно" else ""),
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        grouped.forEach { (b, list) ->
                            item(key = "h$b") {
                                Text(
                                    b,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MIET_BLUE,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                )
                            }
                            items(list, key = { roomKey(it.name) }) { room ->
                                RoomRow(
                                    name = room.name.orEmpty(),
                                    free = showFree,
                                    onClick = { onOpenRoom(room.name.orEmpty()) },
                                )
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
 * Строка списка аудиторий.
 *
 * Отметка «свободна» показывается только когда включён фильтр свободных:
 * без него про свободство утверждать нечего, и галочка была бы враньём.
 */
@Composable
private fun RoomRow(name: String, free: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (free)
                LocalAppColors.current.currentGroup else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (free) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Свободна",
                    tint = LocalAppColors.current.favStar,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}