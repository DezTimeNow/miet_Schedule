package com.mietschedule.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Refresh

/**
 * Избранное: группы, преподаватели и аудитории на трёх вкладках.
 *
 * Раньше избранное было спрятано внутри экрана выбора каждой роли — до него
 * надо было сначала дойти. Теперь это отдельный экран, доступный с любого
 * экрана расписания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    prefs: GroupPrefs,
    onOpen: (Role, String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = "",
    onOpenFavorites: (() -> Unit)? = null,
) {
    // Читаем один раз при входе: дальше перечитываем после каждого изменения.
    var tick by remember { mutableStateOf(0) }
    val favGroups = remember(tick) { prefs.favGroups(Role.STUDENT) }
    val favTeachers = remember(tick) { prefs.favGroups(Role.TEACHER) }
    val favAudiences = remember(tick) { prefs.favGroups(Role.AUDIENCE) }

    val tabs = listOf(
        Role.STUDENT to ("Группы" to favGroups.size),
        Role.TEACHER to ("Преподаватели" to favTeachers.size),
        Role.AUDIENCE to ("Аудитории" to favAudiences.size)
    )
    // Открываем на первой непустой вкладке, а не всегда на «Группы»: иначе
    // пользователь с одним преподавателем в избранном видит пустой экран и
    // думает, что избранное потерялось.
    val firstNonEmpty = tabs.indexOfFirst { it.second.second > 0 }
    var tab by remember { mutableStateOf(if (firstNonEmpty >= 0) firstNonEmpty else 0) }
    val current = tabs[tab]

    Scaffold(
        topBar = {
            val totalFav = favGroups.size + favTeachers.size + favAudiences.size
            MietTopBar(
                title = "Избранное",
                subtitle = if (totalFav == 0) "Пока пусто"
                else "$totalFav " + plural(totalFav, "запись", "записи", "записей"),
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack,
                onOpenFavorites = onOpenFavorites
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MIET_BLUE
            ) {
                tabs.forEachIndexed { i, (_, pair) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(pair.first, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (pair.second == 0) "—"
                                    else "${pair.second} " + plural(pair.second, "", "", "шт."),
                                    fontSize = 10.sp,
                                    color = LocalAppColors.current.muted
                                )
                            }
                        }
                    )
                }
            }

            val items: List<String> = when (current.first) {
                Role.STUDENT -> favGroups
                Role.TEACHER -> favTeachers
                else -> favAudiences
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = LocalAppColors.current.starInactive,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            when (current.first) {
                                Role.STUDENT -> "Добавь группы — нажми на звезду в расписании"
                                Role.TEACHER -> "Добавь преподавателя — нажми на звезду в списке"
                                else -> "Добавь аудиторию — нажми на звезду в списке"
                            },
                            color = LocalAppColors.current.muted, fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items) { value ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(current.first, value) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                val label = when (current.first) {
                                    Role.STUDENT -> "группа"
                                    Role.TEACHER -> "преподаватель"
                                    else -> "аудитория"
                                }
                                Text(label, fontSize = 11.sp, color = LocalAppColors.current.muted)
                            }
                            IconButton(onClick = {
                                prefs.toggleFavFor(current.first, value)
                                tick++
                            }) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = "Убрать из избранного",
                                    tint = LocalAppColors.current.favStar,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
