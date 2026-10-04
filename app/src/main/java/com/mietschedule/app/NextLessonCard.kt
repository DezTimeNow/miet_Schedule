package com.mietschedule.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Данные берутся из кэша расписаний на IO-диспетчере: разбор 344 расписаний
 * на главном потоке давал ANR, а этот блок стоит на первом экране и запускается
 * при каждом открытии меню.
 */

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

    var data by remember(favs) { mutableStateOf(FavLessonData()) }
    LaunchedEffect(favs) {
        val loaded = withContext(Dispatchers.IO) { favLessonData(api, favs) }
        data = loaded
    }

    // Пока кэш читается, карточка уже с парой не нужна: пустая плашка над
    // кнопками мигает и прыгает. Ждём данные.
    if (data.lessons.isEmpty()) return

    // Тик раз в минуту: «через 12 минут» устаревает быстро, а перерисовывать
    // список пар из-за секунд незачем.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val (going, upcoming) = remember(data.lessons, data.times, now) {
        NextLessonLogic.currentAndNext(lessons = data.lessons, times = data.times, now = now)
    }
    if (going == null && upcoming == null) return

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
                Text(
                    "Избранное: что сейчас",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.favStar,
                )
            }
            if (going != null) {
                FavLessonLine(going, now = now, onClick = { openFav(favs, going, onOpen) })
            }
            if (upcoming != null) {
                FavLessonLine(upcoming, now = now, onClick = { openFav(favs, upcoming, onOpen) })
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

/** Пары и таблица времени по всем избранным значениям. */
internal data class FavLessonData(
    val lessons: List<Lesson> = emptyList(),
    val times: List<PairTime> = emptyList(),
)

/**
 * Собрать пары из избранного.
 *
 * Только то, что уже лежит в кэше: сеть на первом экране запускать нельзя,
 * меню открывается мгновенно, а полная загрузка расписаний идёт около
 * тридцати секунд. Если кэша ещё нет — блок просто не покажется, и человек
 * увидит его после первого открытия расписания.
 *
 * Лимит на число избранных: разбор одного расписания занимает заметное
 * время, а избранных групп может быть много. Двадцати хватает — на экране
 * всё равно видны две строки, а не сорок.
 */
internal suspend fun favLessonData(
    api: MietApi,
    favs: List<FavEntry>,
    limit: Int = 20,
): FavLessonData {
    val lessons = ArrayList<Lesson>()
    var times: List<PairTime> = emptyList()
    for (fav in favs.take(limit)) {
        // Кэш групп: если избранное есть, а кэша нет, блок пуст и не мешает.
        val raw = runCatching { api.cachedLessonsOf(fav.role, fav.value) }.getOrNull() ?: continue
        if (raw.isEmpty()) continue
        lessons += raw
        if (times.isEmpty()) times = api.pairTimesFromCache()
    }
    if (lessons.isEmpty()) return FavLessonData()
    return FavLessonData(lessons = lessons, times = mergeTimes(null, lessons))
}

/**
 * Открыть расписание по нажатой строке.
 *
 * Ведёт в расписание той сущности, у которой эта пара. Роль из избранного,
 * не текущая: строка может принадлежать преподавателю, а меню открыто в
 * роли «студент».
 */
private fun openFav(
    favs: List<FavEntry>,
    hit: NextLessonLogic.Hit,
    onOpen: (Role, String) -> Unit,
) {
    // Сначала ищем по названию группы среди избранных — это точное совпадение.
    // Если пара принадлежит преподавателю или аудитории, группа в ней может
    // быть указана, а если нет — берём первый избранный, у которого такая
    // аудитория или фамилия.
    val group = hit.lesson.group?.name
    val room = hit.lesson.room?.name
    val teacher = hit.lesson.classInfo?.teacherFull
    val entry = favs.firstOrNull { !group.isNullOrBlank() && it.value == group }
        ?: favs.firstOrNull { !room.isNullOrBlank() && roomKey(it.value) == roomKey(room) }
        ?: favs.firstOrNull { !teacher.isNullOrBlank() && TeacherIndex.key(it.value) == TeacherIndex.key(teacher) }
        ?: favs.firstOrNull()
    entry ?: return
    onOpen(entry.role, entry.value)
}

/**
 * Строка блока с переходом в расписание.
 */
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
