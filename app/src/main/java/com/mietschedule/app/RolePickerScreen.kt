package com.mietschedule.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.sp

/**
 * Название и слоган мини-игры.
 *
 * Вынесены в константы, потому что используются в трёх местах: на карточке
 * в главном меню, в заголовке экрана игры и в описании релиза. При
 * отдельном написании они со временем разъезжаются.
 */
internal const val GAME_TITLE = "Тапать микросхему"
/**
 * Подпись под названием мини-игры.
 *
 * Требование владельца от 0.49: было «будь лучшим!!» — заменить на
 * «мини-игра». Причина очевидна: надпись звучала как оценка игрока, а не
 * как пояснение, что это вообще такое.
 */
internal const val GAME_TAGLINE = "мини-игра"

/**
 * Первый экран: кто я. Дальше путь зависит от роли — студент выбирает группу,
 * преподаватель ищет себя по фамилии, аудитория выбирается по корпусу.
 */
// FlowRow помечен как экспериментальный в foundation-layout, TopAppBar —
// в material3. Без этих двух opt-in файл не компилируется.
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RolePickerScreen(
    current: Role?,
    onPick: (Role) -> Unit,
    onBack: (() -> Unit)? = null,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    // «Избранное» в главном меню. Раньше кнопка жила в шапке, но там ей
    // не на чем было стоять: рядом со звёздочкой она означала «любить
    // текущую группу», а не «открыть список». Внизу, рядом с «Настройки»
    // и «О программе», она читается однозначно.
    onOpenFavorites: (() -> Unit)? = null,
    // Мини-игра. Кнопка активна всегда: без сети открывается экран с
    // требованием подключиться — так человек сразу видит, что игра есть
    // и почему она недоступна. Требование владельца: без сети игра не
    // работает вовсе, поэтому и локального счёта нет.
    onOpenChipGame: (() -> Unit)? = null,
    refreshing: Boolean = false,
    // Прогресс обновления: обновляются все 344 группы, это ~35 секунд.
    // Без текста ожидание выглядит как зависшее приложение.
    refreshNote: String = ""
) {
    Scaffold(
        topBar = {
            // Первый экран: назад идти некуда, поэтому под стрелкой
            // зарезервировано пустое место той же ширины — иначе «Роль»
            // и обновление стояли бы на других координатах, чем на всех
            // остальных экранах.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "Кто ты?",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = null
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
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
            if (current != null) {
                RoleCard(Role.entries.first { it == current }, true, { onPick(current) })
                Spacer(Modifier.height(20.dp))
                Text(
                    "Сменить роль",
                    color = MIET_BLUE,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                )
                Text(
                    "Посмотреть расписание другой ролью",
                    fontSize = 12.sp, color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            } else {
                Text(
                    "Выбери, чьё расписание смотрим",
                    fontSize = 14.sp, color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            // Список ролей НИЖЕ карточки текущей роли содержит только
            // ОСТАВШИЕСЯ роли. Раньше здесь был полный Role.entries, и уже
            // выбранная роль показывалась дважды: отдельной карточкой наверху
            // («Аудитория») и снова в списке ниже. Выглядело как баг —
            // «зачем мне написано дважды, какое я меню выбрал».
            // Подпись «Посмотреть расписание другой ролью» теперь соответствует
            // содержимому: в списке именно другие роли.
            Role.entries.filter { it != current }.forEach { role ->
                RoleCard(role, false) { onPick(role) }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(24.dp))
            // «О программе» убрана из шапки ради единого места у кнопки
            // обновления, но доступ не потерян: кнопка стоит под списком ролей.
            Spacer(Modifier.height(18.dp))

            // ───────── мини-игра МИЭТ ─────────
            // Отдельная карточка, а не кнопка в ряду: подпись «Тапать
            // микросхему» длиннее соседних и в общем ряду либо обрезалась,
            // либо переносила за собой всю строку. Игра — не служебная
            // кнопка вроде «Настроек», а то, ради чего её запускают, и
            // она стоит выше служебных.
            GameCard(
                onClick = { onOpenChipGame?.invoke() },
                enabled = onOpenChipGame != null,
            )

            Spacer(Modifier.height(14.dp))
            // Настройки и «О программе» — две отдельные кнопки в одном месте.
            // Раньше тему и напоминания приходилось искать внутри «О программе»,
            // а это последний экран перед выходом: настройку ищут перед
            // использованием, а не после.
            // Четыре кнопки в ряд не помещались на узких экранах: на
            // Honor Magic V2 шириной 1200 px при density 3 подписи
            // обрезались. FlowRow переносит их на вторую строку целиком,
            // а не рвёт строку между словами.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center,
            ) {
                // «Избранное» перенесено сюда из шапки. В шапке оно было
                // рядом со звёздочкой и «Обновить», но на других экранах
                // рядом нечего было ставить, и кнопка либо исчезала, либо
                // сдвигала соседей. Здесь она всегда под рукой, рядом с
                // двумя другими кнопками главного меню.
                TextButton(
                    onClick = { onOpenFavorites?.invoke() },
                    enabled = onOpenFavorites != null,
                ) {
                    Text("Избранное", color = MIET_BLUE, fontSize = 13.sp)
                }
                TextButton(onClick = onSettings) {
                    Text("Настройки", color = MIET_BLUE, fontSize = 13.sp)
                }
                TextButton(onClick = onAbout) {
                    Text("О программе", color = MIET_BLUE, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleCard(role: Role, selected: Boolean, onClick: () -> Unit) {
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
            containerColor = if (selected) LocalAppColors.current.currentGroup else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(role.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(role.hint, fontSize = 12.sp, color = LocalAppColors.current.muted)
            }
            Text(
                if (selected) "\u2713" else "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Карточка мини-игры в главном меню.
 *
 * Стоит после списка ролей: выбор роли — это вход в приложение, а игра
 * ни с чем не связана и от роли не зависит.
 */
@Composable
private fun GameCard(onClick: () -> Unit, enabled: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                tint = MIET_BLUE,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    GAME_TITLE,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    GAME_TAGLINE,
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted
                )
            }
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
