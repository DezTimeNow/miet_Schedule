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
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Название и слоган мини-игры.
 *
 * Вынесены в константы, потому что используются в нескольких местах: в
 * заголовке экрана игры и на кнопке в подменю главной. При отдельном
 * написании они со временем разъезжаются.
 *
 * Файл, где они жили раньше (экран главного меню), удалён в 0.62: главная
 * теперь в [HomeScreen], а список ролей — в [RoleSelectScreen].
 */
internal const val GAME_TITLE = "Тапать микросхему"
/**
 * Подпись под названием мини-игры.
 *
 * Требование владельца от 0.49: было «будь лучшим!!» — заменить на
 * «мини-игра». Надпись звучала как оценка игрока, а не как пояснение, что
 * это вообще такое. С 0.62 это же слово стоит на кнопке в подменю главной.
 */
internal const val GAME_TAGLINE = "мини-игра"

/**
 * ГЛАВНАЯ СТРАНИЦА.
 *
 * Требование владельца от 0.62, дословно: «пользователь видит избранное,
 * затем кнопки выбрать роли и Избранное, затем идёт нижнее подменю».
 * Порядок сверху вниз:
 *
 *   1. Блок избранного — то, ради чего приложение открывают чаще всего.
 *      Подпись «Избранное» в нём сделана кнопкой, поэтому отдельной кнопки
 *      «Избранное» в подменю нет: два одинаковых слова на одном экране
 *      читаются как ошибка.
 *   2. Кнопка «Выбрать роль» — одна вместо трёх карточек. Раньше карточки
 *      «Студент / Преподаватель / Аудитория» занимали почти весь первый
 *      экран и вытесняли избранное вниз, за пределы видимой части.
 *   3. Служебное подменю: Настройки, мини-игра, О программе.
 *
 * Порядок задан требованием, а не вкусом: человек открывает приложение,
 * чтобы узнать «что сейчас», и этот ответ обязан быть на первом экране.
 *
 * Прокрутка обязательна: при трёх избранных блок занимает 15 строк, и без
 * прокрутки нижнее подменю уезжает за край и становится недоступным.
 * fillMaxSize() здесь НЕ ставится намеренно — в прокручиваемой колонке он
 * растягивает её и обрезает содержимое.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun HomeScreen(
    /** Данные для блока избранного. */
    api: MietApi,
    prefs: GroupPrefs,
    /** Открыть расписание нажатой строки избранного. */
    onOpenFavorite: (Role, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    /** Открыть экран выбора роли. */
    onPickRole: () -> Unit = {},
    /** Прогресс обновления: обновляются все 344 группы, это ~35 секунд. */
    refreshNote: String = "",
    refreshing: Boolean = false,
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    onOpenChipGame: (() -> Unit)? = null,
    /** Открыть экран избранного из подписи над блоком пар. */
    onOpenFavorites: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            // На главной стрелки «‹» нет: возвращаться некуда, и пустое место
            // той же ширины резервируется, чтобы заголовок стоял на тех же
            // координатах, что на всех остальных экранах.
            //
            // Второй строкой раньше было «Кто ты?». Подпись убрана по
            // требованию владельца: главная теперь не «кто я», а «что сейчас»,
            // и подпись вводила в заблуждение — роль выбирается кнопкой ниже,
            // а не определяется на этом экране.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "",
                onRefresh = onRefresh,
                // Домик в шапке — возврат на главную. На самой главной он
                // ничего не делает, поэтому на этом экране слот пустой.
                onChangeRole = {},
                refreshing = refreshing,
                onBack = null,
                // Слот под иконку «домой»: на главной она ведёт в себя же,
                // и пустая кнопка читалась бы как сломанная.
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

            // ───── ИЗБРАННОЕ ─────
            //
            // Стоит ПЕРЕД всем остальным: человек открывает приложение, чтобы
            // узнать «что сейчас», и ответ обязан быть на первом экране.
            // Рисуется только когда есть чем наполнить — без API или без
            // хранилища (например, в превью) он молча не появляется.
            if (api != null && prefs != null) {
                NextLessonCard(
                    api = api,
                    prefs = prefs,
                    onOpen = onOpenFavorite,
                    // Подпись блока остаётся строкой, а не кнопкой: вход в
                    // избранное теперь даёт карточка ниже, и второй способ
                    // нажатия на то же самое только размывает, куда давить.
                    onOpenFavorites = null,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ───── ВЫБОР РОЛИ И ИЗБРАННОЕ ─────
            //
            // Две карточки в том же виде, что и прежние карточки ролей:
            // иконка, название, стрелка. Текстом они были только в 0.62, и
            // владелец сразу это заметил. Карточек ролей на главной больше
            // нет — они на своём экране, — но вид остался общим для действий
            // главной.
            //
            // Пояснений под названием нет: владелец просил только иконку и
            // название. Подпись и так однозначна, а пустая вторая строка
            // делала бы карточки выше без пользы.
            HomeActionCard(
                icon = Icons.Filled.School,
                title = "Выбрать роль",
                onClick = onPickRole,
            )
            Spacer(Modifier.height(10.dp))
            HomeActionCard(
                icon = Icons.Filled.Star,
                title = "Избранное",
                onClick = { onOpenFavorites?.invoke() },
            )

            Spacer(Modifier.height(18.dp))

            // ───── СЛУЖЕБНОЕ ПОДМЕНЮ ─────
            //
            // Настройки, мини-игра, О программе. Кнопки в ряд: на Honor Magic V2
            // шириной 1200 px при density 3 четыре подписи в линию обрезались,
            // поэтому FlowRow переносит их целиком, а не рвёт строку между
            // словами.
            //
            // «Избранное» в этот ряд НЕ входит: ему отведена своя карточка
            // выше, рядом с «Выбрать роль». В ряду подменю она была бы
            // третьей кнопкой одного и того же действия.
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
                    // подпись читалась как отключённая кнопка. Владелец заметил
                    // это сразу после переноса ссылки вниз.
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
 * СТРОКА-КНОПКА ГЛАВНОЙ.
 *
 * Вид повторяет прежние карточки ролей: иконка, название и стрелка «›»
 * справа. В 0.62 обе строки главной были простым текстом, и это читалось
 * как потеря оформления — карточки ушли вместе с ролями, хотя главной
 * они нужны не меньше.
 *
 * Пояснение под названием не выводится: владелец просил на карточках
 * только иконку и название. Состояния «выбрано» тоже нет — на главной
 * отмечать нечего, выбор происходит на следующих экранах.
 *
 * Карточка не [RoleSelectCard]: та живёт на экране ролей, рисует роль и
 * умеет показывать отметку выбора. Общего у них только вид, и сводить их
 * в одну функцию значило бы тащить сюда роль и признак выбора.
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
 * ЭКРАН ВЫБОРА РОЛИ.
 *
 * Отделён от главной по требованию владельца: три карточки ролей вынесены
 * с первого экрана, чтобы избранное осталось видимым без прокрутки.
 *
 * Это подменю, а не главная: у него есть стрелка «‹» и кнопка «домой» в
 * шапке, и «Назад» возвращает на главную. Смешивать его с главной нельзя —
 * тогда «Назад» с главной пришлось бы вести в список ролей, то есть
 * подменю стало бы целью кнопки выхода.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSelectScreen(
    /** Роль, для которой уже сохранён выбор: показывается отмеченной. */
    current: Role?,
    onPick: (Role) -> Unit,
    onRefresh: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** Домик в шапке — возврат на главную. */
    onHome: () -> Unit = {},
    refreshing: Boolean = false,
) {
    Scaffold(
        topBar = {
            MietTopBar(
                title = "Выбор роли",
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
                "Чьё расписание смотрим",
                fontSize = 14.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Role.entries.forEach { role ->
                RoleSelectCard(role, role == current) { onPick(role) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * Карточка роли на экране выбора.
 *
 * Отдельная функция вместо общей [RoleCard]: та была частью главной и
 * рисовалась в потоке вместе с блоком избранного. Здесь роль — единственное
 * содержимое экрана, и подпись под названием («Моя группа», «Кто и когда в
 * ней») больше не нужна — она объясняла то, что следует из самого выбора.
 */
@Composable
private fun RoleSelectCard(role: Role, selected: Boolean, onClick: () -> Unit) {
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
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                LocalAppColors.current.currentGroup
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.padding(end = 14.dp))
            Text(
                role.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (selected) "✓" else "›",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
