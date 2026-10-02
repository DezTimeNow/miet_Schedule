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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material.icons.filled.SystemUpdate

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.rememberCoroutineScope

import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Refresh


/**
 * Экран «О программе»: версия сборки и как приложение вообще работает.
 *
 * Текст написан нейтрально: он описывает, ЧТО приложение делает, и не
 * объясняет недостатки сайта. Прежние формулировки вроде «сайт не отдаёт»
 * и «136 аудиторий из 221» звучали как обвинение в адрес МИЭТ и вдобавок
 * светили внутренние числа, которые пользователю ничего не дают.
 * Правило: говорим о своём поведении, а не о чужих ошибках.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    onUpdateFound: (UpdateInfo) -> Unit = {},
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = "",
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkMessage by remember { mutableStateOf<String?>(null) }
    // Пересчитываем при возврате на экран: метка «проверено N мин назад» не
    // должна застывать, пока пользователь читает про обновления.
    val lastChecked = UpdateChecker.lastCheckedAt(ctx)
    Scaffold(
        topBar = {
            // Общая шапка: иконка обновления была тут 24.dp, на остальных
            // экранах 22.dp — при разном размере соседа кнопка занимала
            // разный слот. Теперь размер задан один раз, в MietTopBar.
            MietTopBar(
                title = "О программе",
                subtitle = "Расписание МИЭТ",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
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
                "Приложение загружает расписания групп с сайта МИЭТ и " +
                "хранит их на телефоне. Повторно в интернет почти не ходит, " +
                "поэтому открывается быстро и работает даже без сети."
            )

            AboutBlock(
                "Студент",
                "Расписание своей группы берётся напрямую у сайта, пар " +
                "приходят вместе с точным временем начала и конца."
            )

            AboutBlock(
                "Преподаватель",
                "Отдельного расписания преподавателя на сайте нет, поэтому " +
                "приложение собирает его само: просматривает расписания " +
                "групп и оставляет те пары, где этот преподаватель ведёт " +
                "занятие. Список преподавателей получается сам собой, искать " +
                "в нём можно по фамилии."
            )

            AboutBlock(
                "Аудитория",
                "Занятость аудитории приложение собирает из тех же расписаний " +
                "групп: находит пары, которые проходят в этом помещении, и " +
                "склеивает их в один список. Помещения узнаются по названию, " +
                "поэтому «8307» и «8307 к» — это разные аудитории, а не одна."
            )

            AboutBlock(
                "Данные и интернет",
                "Расписания групп кэшируются на телефоне, поэтому повторно " +
                "приложение почти не обращается к сайту и работает быстро. " +
                "Кнопка «Обновить» в верхней панели принудительно забирает " +
                "свежие данные. Если интернета нет — показывается то, что уже " +
                "сохранено."
            )

            AboutBlock(
                "Обновления",
                "При запуске приложение проверяет на GitHub, не вышел ли " +
                "новый релиз, и само предлагает его скачать. Проверка идёт " +
                "не чаще раза в 6 часов, чтобы не выжигать лимит запросов " +
                "GitHub. Скачанный файл сверяется по контрольной сумме — " +
                "битый или подменённый APK не установится."
            )

            Spacer(Modifier.height(6.dp))

            // ───────── кнопка «Проверить обновления» ─────────
            // Обновление самой проверки: кнопка спрашивает GitHub напрямую,
            // минуя 6-часовой кэш, поэтому кнопка в шапке (перезагрузка
            // расписания) тут не годится — это другое.
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    checkMessage = null
                    scope.launch {
                        val result = UpdateChecker.checkNow(ctx, versionCode)
                        checking = false
                        when (result) {
                            is UpdateCheckResult.Available -> {
                                checkMessage = "Доступна ${result.info.versionLabel}"
                                onUpdateFound(result.info)
                            }
                            is UpdateCheckResult.UpToDate -> checkMessage =
                                "Установлена свежая версия (на GitHub v${result.latestVersion})"
                            is UpdateCheckResult.Failed -> checkMessage = result.reason
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MIET_BLUE,
                    disabledContainerColor = Color(0xFFBBDEFB),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (checking) "Проверяю…" else "Проверить обновления",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }

            checkMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = Color(0xFF546E7A),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                lastCheckLabel(lastChecked),
                fontSize = 12.sp,
                color = Color(0xFF78909C),
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** «Проверено: сегодня в 14:32» / «Обновления ещё не проверялись». */
internal fun lastCheckLabel(atMillis: Long): String {
    if (atMillis <= 0L) return "Обновления ещё не проверялись"
    val diff = System.currentTimeMillis() - atMillis
    val mins = diff / 60_000
    val hours = mins / 60
    return when {
        mins < 1 -> "Обновления проверены только что"
        mins < 60 -> "Обновления проверены $mins мин назад"
        hours < 24 -> "Обновления проверены $hours ч назад"
        else -> "Обновления проверены ${hours / 24} дн назад"
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
