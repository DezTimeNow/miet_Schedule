package com.mietschedule.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * БЛОК «СЕЙЧАС И ДАЛЬШЕ» В ГЛАВНОМ МЕНЮ.
 *
 * Требование владельца: видеть текущую и следующую пару не внутри расписания,
 * а в самом главном меню — над кнопками «Студент / Преподаватель / Аудитория».
 * Раньше блок стоял только на экране расписания, то есть добраться до него
 * можно было лишь после выбора группы: человек, который ещё не открыл
 * расписание, вообще не видел, что у него сейчас.
 *
 * Условие владельца — «при условии, что я добавил в избранное». Блок строится
 * ПО ИЗБРАННОМУ, а не по текущему выбору: в меню может быть выбрана одна
 * группа, а человек хочет знать про другую, добавленную в избранное. При
 * пустом избранном блока нет — показывать нечего, и пустая плашка только
 * занимала бы место над кнопками ролей.
 *
 * Данные берутся из кэша расписаний на IO-диспетчере: разбор 343 расписаний
 * на главном потоке давал ANR, а этот блок стоит на первом экране и запускается
 * при каждом открытии меню.
 */

/** Высота заглушки блока, пока читается кэш расписаний. */
private val BLOCK_PLACEHOLDER_HEIGHT = 96.dp

/**
 * Подпись блока на главной.
 *
 * Требование владельца от 0.66: «верхний блок Избранное переименовать в
 * ближайшие пары». Название точнее описывает содержимое: в блоке не список
 * избранного, а его ближайшие пары — что идёт сейчас и что дальше.
 */
internal const val FAV_BLOCK_TITLE = "Ближайшие пары"

@Composable
fun NextLessonCard(
    api: MietApi,
    prefs: GroupPrefs,
    /** Открыть расписание: роль и значение из избранного. */
    onOpen: (Role, String) -> Unit,
) {
    // Избранное читается из хранилища, а Compose об этом не знает: значение
    // живёт в state, иначе добавление звезды не отражалось бы здесь до
    // перезапуска экрана.
    val favs = remember { favSnapshot(prefs) }
    if (favs.isEmpty()) return

    var groups by remember(favs) { mutableStateOf<List<FavLessons>>(emptyList()) }
    LaunchedEffect(favs) {
        val loaded = withContext(Dispatchers.IO) { favLessonData(api, favs) }
        groups = loaded
    }

    // ЗАГЛУШКА НА ВРЕМЯ ЧТЕНИЯ КЭША.
    //
    // Раньше блок в это время не рисовал ничего, и главная стояла пустой
    // сверху: список избранного и кнопка «Добавить» подпрыгивали вниз, когда
    // данные приходили. Замер на эмуляторе: блок наполнялся в разы позже,
    // чем рисовалась вся остальная главная.
    //
    // Высота заглушки фиксированная, поэтому прыжок ограничен одним
    // переходом «заглушка → содержимое». Точно зарезервировать высоту
    // нельзя: она зависит от числа избранных, и угадать её заранее нечем.
    if (groups.isEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(BLOCK_PLACEHOLDER_HEIGHT),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = LocalAppColors.current.favStar,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    FAV_BLOCK_TITLE,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.favStar,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "загружаю…",
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                )
            }
        }
        return
    }

    // Тик раз в минуту: «через 12 минут» устаревает быстро, а перерисовывать
    // список пар из-за секунд незачем.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    // По каждому избранному СВОЯ пара строк: идущая и следующая. Общий
    // currentAndNext по всем сразу возвращал одну самую раннюю пару, и второе
    // избранное в блоке просто отсутствовало.
    val rows = remember(groups, now) {
        groups.mapNotNull { g ->
            val (going, upcoming) = NextLessonLogic.currentAndNext(
                lessons = g.lessons, times = g.times, now = now,
            )
            if (going == null && upcoming == null) null
            else FavRows(g.entry, going, upcoming)
        }
    }
    if (rows.isEmpty()) return

    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = LocalAppColors.current.favStar,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                // Подпись блока — просто строка. С 0.65 список избранного
                // стоит на главной отдельными кнопками, и второго входа в
                // него здесь быть не должно.
                Text(
                    FAV_BLOCK_TITLE,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.favStar,
                )
            }
            // Группы по избранному: заголовок отмечает, кому принадлежит пара.
            // Иначе при двух избранных строки выглядели бы как одна лента, и
            // непонятно, чья это пара.
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(
                        color = LocalAppColors.current.muted.copy(alpha = 0.18f),
                    )
                }
                Text(
                    favTitle(row.entry),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
                )
                row.going?.let {
                    FavLessonLine(it, now = now) { onOpen(row.entry.role, row.entry.value) }
                }
                row.upcoming?.let {
                    FavLessonLine(it, now = now) { onOpen(row.entry.role, row.entry.value) }
                }
            }
        }
    }
}

/** Строка блока с переходом в расписание. */
@Composable
private fun FavLessonLine(
    hit: NextLessonLogic.Hit,
    now: Long,
    onClick: () -> Unit,
) {
    val start = hit.start
    val teacher = hit.lesson.classInfo?.teacherFull?.takeIf { it.isNotBlank() }
    val room = hit.lesson.room?.name?.takeIf { it.isNotBlank() }
    val going = NextLessonLogic.isGoing(hit, now)

    val whenText = when {
        going -> "сейчас"
        start != null -> hmText(start)
        else -> ""
    }.trim()

    val counter = if (going) "" else start?.let { counterText(it, now) }.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // ИДУЩАЯ ПАРА ЗАЛИТА ЦВЕТОМ ТЕКУЩЕЙ.
            //
            // Тот же цвет, что у карточки идущей пары на экране расписания
            // (NextLessonRow): одно значение в двух местах означает одно и то
            // же — «идёт сейчас». Пользователь учится этому один раз.
            //
            // Цвет берётся из темы: в тёмной теме это тёмно-синий, потому что
            // светлая заливка на чёрном фоне слепит.
            //
            // Заливка ставится ДО padding: фон рисуется по всей строке, а не
            // только под текстом, иначе подсветка выглядела бы обрезанной по
            // краям. На измерение это не влияет — background только рисует,
            // поэтому строки не сдвигаются при появлении отметки.
            .then(
                if (going) Modifier.background(LocalAppColors.current.currentGroup) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MIET_BLUE,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(hit.lesson.classInfo?.name ?: "Пара")
                        if (whenText.isNotEmpty()) {
                            append(" • ")
                            append(whenText)
                        }
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                val details = buildString {
                    val g = hit.lesson.group?.name
                    if (!g.isNullOrBlank()) append(g)
                    if (room != null) {
                        if (isNotEmpty()) append("  •  ")
                        append("ауд. $room")
                    }
                    if (teacher != null) {
                        if (isNotEmpty()) append("  •  ")
                        append(teacher)
                    }
                }
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        fontSize = 11.sp,
                        color = LocalAppColors.current.muted,
                        maxLines = 2,
                    )
                }
            }
            if (counter.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    counter,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MIET_BLUE,
                )
            }
        }
    }
}

/**
 * Снимок избранного по всем ролям.
 *
 * Всё избранное, а не только текущей роли: человек может смотреть меню в
 * роли «студент», а в избранном держать преподавателя, и блок обязан показать
 * его пары. Снимок — обычная строка, поэтому его можно класть в ключ
 * remember и сравнивать покомпонентно.
 */
internal data class FavEntry(val role: Role, val value: String)

internal fun favSnapshot(prefs: GroupPrefs): List<FavEntry> =
    Role.entries.flatMap { role ->
        prefs.favGroups(role).sorted().map { FavEntry(role, it) }
    }

/**
 * Пары ОДНОГО избранного значения вместе с его таблицей времени.
 *
 * Раньше здесь был один список `lessons` на всё избранное, и `currentAndNext`
 * искал по нему самую раннюю пару. При двух избранных — группа и
 * преподаватель — победила та, чья пара начиналась раньше, а вторая молча
 * исчезала: блок отвечал на вопрос «что сейчас» один раз, а не по каждому.
 */
internal data class FavLessons(
    val entry: FavEntry,
    val lessons: List<Lesson> = emptyList(),
    val times: List<PairTime> = emptyList(),
)

/**
 * Готовые к отрисовке строки одного избранного.
 *
 * Раздельно от [FavLessons], потому что здесь уже только то, что рисуется:
 * пара без времени не показывается, а избранное без пары пропускается целиком.
 */
internal data class FavRows(
    val entry: FavEntry,
    val going: NextLessonLogic.Hit?,
    val upcoming: NextLessonLogic.Hit?,
)

/** Подпись группы строк: чему принадлежит пара. */
internal fun favTitle(entry: FavEntry): String = when (entry.role) {
    Role.STUDENT -> entry.value
    Role.TEACHER -> entry.value
    Role.AUDIENCE -> "ауд. ${entry.value}"
}

/**
 * Собрать пары по каждому избранному отдельно.
 *
 * Требование владельца: при двух-трёх избранных показывать информацию по
 * всем сразу, а не по одному. Раньше пары всех избранных складывались в общий
 * список, и `currentAndNext` возвращал одну самую раннюю пару на всё
 * избранное — то есть блок молчал про второе и третье.
 *
 * Только то, что уже лежит в кэше: сеть на первом экране запускать нельзя,
 * меню открывается мгновенно, а полная загрузка расписаний идёт около
 * тридцати секунд. Если кэша нет — избранное просто не попадёт в блок.
 *
 * Лимит на число избранных: разбор одного расписания занимает заметное
 * время. Двадцати хватает, на экране всё равно видны не все.
 *
 * @return по одному [FavLessons] на избранное, у которого есть кэш. Пустых
 *   нет: показывать строку «пар нет» незачем — блок отвечает на вопрос
 *   «что сейчас», и такая строка на него не отвечает.
 */
internal suspend fun favLessonData(
    api: MietApi,
    favs: List<FavEntry>,
    limit: Int = 20,
): List<FavLessons> {
    val out = ArrayList<FavLessons>()
    // Таблица времени читается ОДИН раз на весь блок, а не на каждое
    // избранное. pairTimesFromCache разбирает JSON до двенадцати расписаний
    // групп, и вызов внутри цикла при трёх избранных давал до 36 разборов
    // ради одной таблицы. Хуже того, результат выбрасывался: в mergeTimes
    // уходил null, и время бралось только из самих пар — то есть таблица
    // выходила неполной для пар, которых у группы в её расписании нет.
    val table = runCatching { api.pairTimesFromCache() }.getOrDefault(emptyList())
    for (fav in favs.take(limit)) {
        val raw = runCatching { api.cachedLessonsOf(fav.role, fav.value) }.getOrNull() ?: continue
        if (raw.isEmpty()) continue
        out += FavLessons(entry = fav, lessons = raw, times = mergeTimes(table, raw))
    }
    return out
}

private fun hmText(millis: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        java.util.Locale.US, "%02d:%02d",
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}

/**
 * «через 25 мин», «завтра 10:40», «Пн 09:00».
 *
 * Правило то же, что в NextLessonRow. Две копии здесь по необходимости:
 * функция там private, а блок стоит в другом файле. При правке править обе —
 * иначе на расписании и в меню будет разное время одной и той же пары.
 */
private fun counterText(start: Long, now: Long): String {
    val mins = ((start - now) / 60_000).toInt()
    if (mins <= 0) return "сейчас"
    if (mins < 60) return "через $mins мин"

    val days = Math.round((midnight(start) - midnight(now)) / (24.0 * 3600 * 1000)).toInt()
    if (days == 1) return "завтра " + hmText(start)
    if (days in 2..6) {
        val idx = dayIndexFromCalendar(
            java.util.Calendar.getInstance().apply { timeInMillis = start }.get(java.util.Calendar.DAY_OF_WEEK)
        )
        return "${listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").getOrElse(idx) { "" }} " + hmText(start)
    }
    val hours = mins / 60
    val rem = mins % 60
    return if (rem == 0) "через $hours ч" else "через $hours ч $rem мин"
}

private fun midnight(v: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = v
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
