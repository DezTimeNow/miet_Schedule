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
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp

/**
 * Первый экран: кто я. Дальше путь зависит от роли — студент выбирает группу,
 * преподаватель ищет себя по фамилии, аудитория выбирается по корпусу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePickerScreen(
    current: Role?,
    onPick: (Role) -> Unit,
    onBack: (() -> Unit)? = null,
    onRefresh: () -> Unit = {},
    onAbout: () -> Unit = {},
    refreshing: Boolean = false
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MIET_BLUE),
                title = {
                    Column {
                        Text("Расписание МИЭТ", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Кто ты?", color = Color(0xFFBBDEFB), fontSize = 12.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White
                        ) else Text("\u21bb", color = Color.White, fontSize = 20.sp)
                    }
                    IconButton(onClick = onAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "О программе", tint = Color.White)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
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
                    fontSize = 12.sp, color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            } else {
                Text(
                    "Выбери, чьё расписание смотрим",
                    fontSize = 14.sp, color = Color.Gray,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            Role.entries.forEach { role ->
                RoleCard(role, role == current) { onPick(role) }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(24.dp))
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
            containerColor = if (selected) Color(0xFFBBDEFB) else Color.White
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
                Text(role.hint, fontSize = 12.sp, color = Color.Gray)
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