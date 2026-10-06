package com.mietschedule.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Название и слоган мини-игры.
 *
 * Вынесены в константы, потому что используются в нескольких местах: в
 * заголовке экрана игры и на кнопке в подменю главной. При отдельном
 * написании они со временем разъезжаются.
 */
internal const val GAME_TITLE = "Тапать микросхему"
/**
 * Подпись под названием мини-игры.
 *
 * Требование владельца от 0.49: было «будь лучшим!!» — заменить на
 * «мини-игра». Надпись звучала как оценка игрока, а не как пояснение, что
 * это вообще такое. Здесь же стоит на кнопке в подменю главной.
 */
internal const val GAME_TAGLINE = "мини-игра"

/** Заголовок списка избранного на главной. */
internal const val FAV_LIST_TITLE = "Избранное:"

/**
 * Пояснение при пустом избранном.
 *
 * Показывается вместо списка, пока не отмечено ни одной звезды. Пустое место
 * под заголовком читается как поломка, поэтому здесь сказано, что сделать,
 * чтобы список наполнился.
 */
internal const val FAV_EMPTY_HINT = "Отметь звездой группу — расписание появится здесь"

/**
 * ГЛАВНАЯ СТРАНИЦА.
 *
 * Требование владельца от 0.65: «кнопку избранное и меню избранное убираем с
 * главной, вместо неё делаем на главной список Избранное и дальше кнопки для
 * быстрого доступа на расписание, которое было добавлено в избранное».
 *
 * Порядок сверху вниз:
 *
 *   1. Блок «сейчас и дальше» — то, ради чего приложение открывают чаще
 *      всего. Остаётся сверху по требованию владельца: он отвечает на вопрос
 *      «что сейчас», а список — навигация, и смешивать их значило бы снова
 *      потерять ответ на первый вопрос.
 *   2. Список избранного: по кнопке на каждую отмеченную группу,
 *      преподавателя и аудиторию. Кнопка открывает расписание сразу, минуя
 *      выбор роли и список групп.
 *   3. Кнопка «Добавить» — единственный вход в выбор группы, преподавателя
 *      и аудитории.
 *   4. Служебное подменю: Настройки, мини-игра, О программе.
 *
 * Список и блок строятся из ОДНОГО снимка избранного, но показываются
 * раздельно намеренно: блок молчит про избранное, у которого нет кэша
 * расписаний, а кнопка в списке обязана быть всегда — иначе избранное
 * выглядело бы потерянным сразу после установки.
 *
 * Прокрутка обязательна: при трёх избранных блок занимает полтора десятка
 * строк, и без прокрутки нижнее подменю уезжает за край. fillMaxSize() здесь
 * НЕ ставится намеренно — в прокручиваемой колонке он растягивает её и
 * обрезает содержимое.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun HomeScreen(
    /** Данные для блока «сейчас и дальше». */
    api: MietApi,
    prefs: GroupPrefs,
    /** Открыть расписание: роль и значение избранного. */
    onOpenFavorite: (Role, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    /** Открыть выбор группы / преподавателя / аудитории для добавления. */
    onAdd: () -> Unit = {},
    /** Прогресс обновления: обновляются все 343 группы, это ~35 секунд. */
    refreshNote: String = "",
    refreshing: Boolean = false,
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    onOpenChipGame: (() -> Unit)? = null,
) {
    // Снимок избранного читается на каждом входе на главную. remember без
    // ключа здесь безопасен: при переходе на другой экран главная выходит из
    // композиции (переходы идут через `when (screen)`), поэтому возврат
    // пересоздаёт её и список перечитывается. Без этого снятая звезда
    // оставалась бы в списке до перезапуска приложения.
    val favs = remember { favSnapshot(prefs) }

    Scaffold(
        topBar = {
            // На главной стрелки «‹» нет: возвращаться некуда, и пустое место
            // той же ширины резервируется, чтобы заголовок стоял на тех же
            // координатах, что на всех остальных экранах.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "",
                onRefresh = onRefresh,
                // Домик в шапке ведёт на главную, то есть сам в себя; слот
                // пустой, иначе кнопка читалась бы как сломанная.
                onChangeRole = {},
                refreshing = refreshing,
                onBack = null,
                homeSlotEmpty = true,
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    fontSize = 12.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                )
            }

            // ───── ЧТО СЕЙЧАС И ЧТО ДАЛЬШЕ ─────
            //
            // Рисуется только когда есть чем наполнить — без API или без
            // хранилища (например, в превью) он молча не появляется.
            if (api != null && prefs != null) {
                NextLessonCard(
                    api = api,
                    prefs = prefs,
                    onOpen = onOpenFavorite,
                )
            }

            Spacer(Modifier.height(14.dp))

            // ───── СПИСОК ИЗБРАННОГО ─────
            //
            // Быстрый доступ: одна кнопка на избранное, расписание
            // открывается сразу. Звезда снимается внутри расписания, в шапке,
            // поэтому отдельного экрана избранного нет.
            Text(
                FAV_LIST_TITLE,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            if (favs.isEmpty()) {
                Text(
                    FAV_EMPTY_HINT,
                    fontSize = 13.sp,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
            } else {
                favs.forEach { entry ->
                    FavEntryCard(entry) { onOpenFavorite(entry.role, entry.value) }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // ───── ДОБАВЛЕНИЕ ─────
            //
            // Единственный вход в выбор группы, преподавателя и аудитории.
            // Раньше на этом месте стояли две карточки — «Выбрать роль» и
            // «Избранное»; роль перестала быть постоянным понятием интерфейса
            // и нужна только как шаг добавления.
            HomeActionCard(
                icon = Icons.Filled.Add,
                title = "Добавить",
                onClick = onAdd,
            )

            Spacer(Modifier.height(18.dp))

            // ───── СЛУЖЕБНОЕ ПОДМЕНЮ ─────
            //
            // Кнопки в ряд: на Honor Magic V2 шириной 1200 px при density 3
            // четыре подписи в линию обрезались, поэтому FlowRow переносит их
            // целиком, а не рвёт строку между словами.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onSettings) {
                    Text("Настройки", color = MIET_BLUE, fontSize = 13.sp)
                }
                TextButton(
                    onClick = { onOpenChipGame?.invoke() },
                    enabled = onOpenChipGame != null,
                ) {
                    // Цвет MIET_BLUE, а не приглушённый: мини-игра — такой же
                    // доступ к экрану, как настройки и «О программе», и серая
                    // подпись читалась как отключённая кнопка.
                    Text(GAME_TAGLINE, color = MIET_BLUE, fontSize = 13.sp)
                }
                TextButton(onClick = onAbout) {
                    Text("О программе", color = MIET_BLUE, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * КНОПКА ИЗБРАННОГО НА ГЛАВНОЙ.
 *
 * Иконка по роли, само значение и стрелка. Открывает расписание сразу —
 * именно это требование владельца от 0.65: «кнопки для быстрого доступа на
 * расписание, которое было добавлено в избранное».
 */
@Composable
private fun FavEntryCard(entry: FavEntry, onClick: () -> Unit) {
    val icon: ImageVector = when (entry.role) {
        Role.STUDENT -> Icons.Filled.School
        Role.TEACHER -> Icons.Filled.Person
        Role.AUDIENCE -> Icons.Filled.Apartment
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                favTitle(entry),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * СТРОКА-КНОПКА ГЛАВНОЙ.
 *
 * Вид повторяет прежние карточки ролей: иконка, название и стрелка «›»
 * справа. Пояснение под названием не выводится: подпись однозначна, а
 * пустая вторая строка делала бы карточку выше без пользы.
 */
@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Что добавляется в избранное на экране добавления.
 *
 * Отдельная подпись, а не [Role.title]: роль называет точку зрения («Студент»),
 * а здесь выбирают, ЧТО добавить, — и для роли студента это группа.
 */
internal fun addLabel(role: Role): String = when (role) {
    Role.STUDENT -> "Группа"
    Role.TEACHER -> "Преподаватель"
    Role.AUDIENCE -> "Аудитория"
}

/**
 * ЭКРАН ДОБАВЛЕНИЯ: что добавить в избранное.
 *
 * Требование владельца от 0.65: выбор группы, преподавателя и аудитории
 * вызывается кнопкой «Добавить» с главной и нужен только как шаг добавления.
 * Отметки выбранной роли здесь нет и подсветки нет: это меню выбора, а не
 * показ состояния — какая роль и что выбрано, видно по расписанию, которое
 * эти кнопки открывают.
 *
 * Стрелка «‹» возвращает туда, откуда вошли, домик — всегда на главную.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPickerScreen(
    onPick: (Role) -> Unit,
    onRefresh: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onHome: () -> Unit = {},
    refreshing: Boolean = false,
) {
    Scaffold(
        topBar = {
            MietTopBar(
                title = "Добавить",
                subtitle = "",
                onRefresh = onRefresh,
                onChangeRole = onHome,
                refreshing = refreshing,
                onBack = onBack,
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Что добавить в избранное",
                fontSize = 14.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Role.entries.forEach { role ->
                AddPickerCard(role) { onPick(role) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * Карточка выбора на экране добавления.
 *
 * Состояния «выбрано» нет: это меню выбора, а не показ состояния. Раньше
 * текущая роль отмечалась галочкой вместо стрелки и подсветкой фона, из-за
 * чего экран читался как «где я нахожусь», хотя открывают его, чтобы выбрать,
 * что добавить.
 */
@Composable
private fun AddPickerCard(role: Role, onClick: () -> Unit) {
    val icon: ImageVector = when (role) {
        Role.STUDENT -> Icons.Filled.School
        Role.TEACHER -> Icons.Filled.Person
        Role.AUDIENCE -> Icons.Filled.Apartment
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.padding(end = 14.dp))
            Text(
                addLabel(role),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
