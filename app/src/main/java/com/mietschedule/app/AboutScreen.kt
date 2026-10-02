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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Экран «О программе»: версия сборки и как приложение вообще работает.
 *
 * Текст принципа работы написан по факту реализации, а не для красоты:
 * расписание преподавателя сайт не отдаёт, аудиторий на сайте 136 из 221, а
 * преподавателей 681 — всё это собрано из расписаний групп, и без этого
 * половина приложения выглядела бы пустышкой.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, versionCode: Int, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MIET_BLUE),
                title = {
                    Column {
                        Text("О программе", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Расписание МИЭТ", color = Color(0xFFBBDEFB), fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Text("\u2039", color = Color.White, fontSize = 28.sp)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFBBDEFB)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Расписание МИЭТ", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                        Text(
                            "Версия $versionName (сборка $versionCode)",
                            fontSize = 13.sp, color = Color(0xFF0D47A1)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            AboutBlock(
                "Как это работает",
                "Приложение обращается к сайту МИЭТ и забирает расписания. " +
                "Но сайт отдаёт их не для всех. Поэтому часть расписаний " +
                "приложение собирает само."
            )

            AboutBlock(
                "Студент",
                "Расписание своей группы берётся напрямую у сайта, пар " +
                "приходят вместе с точным временем начала и конца."
            )

            AboutBlock(
                "Преподаватель",
                "Такого расписания сайт не отдаёт вообще. Приложение один " +
                "раз обходит расписания всех групп и собирает из них пары " +
                "этого преподавателя — получается 661 человек. Дальше всё " +
                "берётся из локального кэша, без обращения к сайту."
            )

            AboutBlock(
                "Аудитория",
                "Сайт знает только 136 аудиторий из 221. Остальные — весь " +
                "корпус 8, корпус 6, УВЦ, виртуальные — он не отдаёт вообще, " +
                "поэтому приложение собирает их занятость из кэша расписаний " +
                "групп. Аудитория опознаётся по имени: номер в названии и " +
                "внутренний код сайта у разных помещений совпадают."
            )

            AboutBlock(
                "Данные и интернет",
                "Расписания групп кэшируются на телефоне, поэтому повторно " +
                "приложение почти не обращается к сайту и работает быстро. " +
                "Кнопка «Обновить» в верхней панели принудительно забирает " +
                "свежие данные. Если интернета нет — показывается то, что уже " +
                "сохранено."
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutBlock(title: String, body: String) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MIET_BLUE)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, color = Color(0xFF1A1A1A))
        }
    }
}
