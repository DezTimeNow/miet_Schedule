package com.mietschedule.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ширина, которую занимает стрелка «‹» в шапке.
 *
 * Она нужна как ЗАРЕЗЕРВИРОВАННОЕ место: на экранах, где назад есть, здесь
 * стоит стрелка, а где назад некуда (выбор роли) — пустота той же ширины.
 * Без этого резерва «Роль» и кнопка обновления прыгали бы между экранами.
 */
private val BACK_SLOT = 48.dp

/**
 * ЕДИНАЯ ШАПКА для всех экранов приложения.
 *
 * Почему вынесено в отдельный файл, а не повторяется в каждом экране:
 * требование БОССа — «меню должно быть одинаковое у всех ролей» и «кнопка
 * обновления всегда в одном месте». Пока каждый экран собирал свой блок
 * `actions` руками, они разъезжались: у студента в шапке стоял текст
 * «Сменить роль» СЛЕВА от обновления, у преподавателя и аудитории — стрелка
 * «‹» в navigationIcon. Ширина соседей отличалась, и кнопка обновления
 * измерялась то на 854 px, то на 728 px: код был похож, слот — разный.
 *
 * Теперь набор соседей задан один раз, и все экраны обязаны использовать
 * именно его. Слот кнопки обновления становится функцией от этого набора,
 * а не от того, что дописал разработчик в конкретном экране.
 *
 * СОСТАВ (слева направо), одинаковый на всех экранах:
 *   [‹ назад] [★] [⭯ обновить] [Роль]
 *
 * Звезда присутствует только там, где есть что любить (расписание), но место
 * под неё зарезервировано всегда — иначе она снова сдвинет соседей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MietTopBar(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit,
    onChangeRole: () -> Unit,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    isFav: Boolean? = null,
    onToggleFav: (() -> Unit)? = null,
    // Список избранного. Раньше он жил в строке вкладок и появлялся только
    // при переходе на другую неделю — то есть «зафиксировать избранное в
    // одном месте» было невозможно. Теперь кнопка живёт в шапке, рядом со
    // звездой, и доступна всегда. Резерв под неё задан на всех экранах.
    onOpenFavorites: (() -> Unit)? = null,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MIET_BLUE),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Text("‹", color = Color.White, fontSize = 28.sp)
                }
            } else {
                Spacer(Modifier.width(BACK_SLOT))
            }
        },
        title = {
            Column {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFFBBDEFB), fontSize = 12.sp)
            }
        },
        actions = {
            if (isFav != null) {
                IconButton(onClick = { onToggleFav?.invoke() }) {
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFav) "Убрать из избранного" else "В избранное",
                        tint = if (isFav) Color(0xFFFFC107) else Color(0xFFBBDEFB),
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                // Резерв под звезду. Иначе на экране расписания (где звезда
                // есть) обновление стояло бы на 48 px левее, чем на остальных.
                Spacer(Modifier.width(BACK_SLOT))
            }
            if (onOpenFavorites != null) {
                IconButton(onClick = { onOpenFavorites.invoke() }) {
                    Icon(
                        Icons.Filled.List,
                        contentDescription = "Избранное",
                        tint = Color(0xFFBBDEFB),
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(BACK_SLOT))
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                // 343 группы — это ~50 секунд, поэтому вместо иконки крутилка:
                // молчащее ожидание читается как зависание.
                if (refreshing) CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White
                ) else Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Обновить всё",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            TextButton(onClick = onChangeRole) {
                Text("Роль", color = Color(0xFFBBDEFB), fontSize = 13.sp)
            }
        }
    )
}
