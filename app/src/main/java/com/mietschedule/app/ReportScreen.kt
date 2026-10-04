package com.mietschedule.app

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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Экран «Сообщить об ошибке».
 *
 * Сделан отдельным экраном, а не диалогом, потому что здесь есть что
 * показать: сводка состояния приложения и необязательный комментарий.
 * Пользователю не нужно ничего описывать — версия, роль и состояние
 * данных отправляются сами, см. [ReportData].
 * Сделан отдельным экраном, а не диалогом, потому что здесь есть что
 * показать: сводка состояния приложения и необязательный комментарий.
 * Версия, роль и состояние данных отправляются сами, см. [ReportData],
 * поэтому описывать ничего не нужно.
 *
 * Пояснений под списком полей нет намеренно: человек отправляет отчёт
 * молча, и текст «комментарий можно не писать» только занимал место.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: ReportData,
    onBack: () -> Unit,
    /**
     * Проверить обновления приложения — действие кнопки ⭯ в шапке.
     *
     * Отдельный параметр, а не заглушка: тот же вызов из «О программе»
     * открывает диалог загрузки, и на экране отчёта он тоже уместен.
     */
    onCheckUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var rateLeft by remember { mutableStateOf(ReportSender.leftRateLimitMs(ctx)) }

    Scaffold(
        topBar = {
            MietTopBar(
                title = "Сообщить об ошибке",
                subtitle = "Расписание МИЭТ",
                // Шапка единая, и ⭯ на этом экране тоже должна быть
                // настоящей кнопкой. Заглушка onRefresh = onBack была
                // ловушкой: тап по обновлению молча возвращал в
                // «О программе», и человек решал, что кнопка сломана.
                // Здесь ⭯ и «Меню» ведут в «О программе» — оттуда этот
                // экран и открывается, и там же живёт «Проверить обновления».
                // ⭯ на экране отчёта проверяет обновления приложения: тут
                // нечего обновлять в расписании, а заглушка onRefresh =
                // onBack молча возвращала в «О программе», и человек решал,
                // что кнопка сломана.
                onRefresh = onCheckUpdate,
                onChangeRole = onBack,
                onBack = onBack,
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
            // ───── сводка: то, что уходит в письме ─────
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = LocalAppColors.current.currentGroup
                ),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(2.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "В письме будет",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    ReportLine("Версия", report.versionName)
                    ReportLine("Роль", report.role.title)
                    ReportLine("Пар на экране", report.lessonsShown.toString())
                    ReportLine(
                        "Состояние данных",
                        if (report.errorText.isNullOrBlank()) "ошибок нет" else "ошибка на экране",
                    )
                    if (!report.errorText.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            report.errorText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ───── комментарий, необязательный ─────
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Что не так (необязательно)", fontSize = 13.sp) },
                placeholder = {
                    Text("Например: расписание не открывается", fontSize = 13.sp)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = !sending && !sent,
            )

            Spacer(Modifier.height(14.dp))

            if (sent) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Вернуться в «О программе»", fontSize = 14.sp)
                }
            } else {
                Button(
                    onClick = {
                        if (sending) return@Button
                        sending = true
                        message = null
                        scope.launch {
                            when (val r = ReportSender.send(ctx, report, comment)) {
                                is ReportSender.Result.Sent -> {
                                    sent = true
                                    message = "Спасибо, отчёт отправлен"
                                }
                                is ReportSender.Result.TooSoon -> {
                                    message = "Уже отправлено. ${ReportSender.rateLimitLabel(r.minutesLeft.toLong() * 60_000L)}"
                                    rateLeft = r.minutesLeft.toLong() * 60_000L
                                }
                                is ReportSender.Result.Failed -> message = r.reason
                            }
                            sending = false
                        }
                    },
                    enabled = !sending && rateLeft <= 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MIET_BLUE,
                        disabledContainerColor = LocalAppColors.current.currentGroup,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.surface,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            sending -> "Отправляю…"
                            rateLeft > 0 -> ReportSender.rateLimitLabel(rateLeft)
                            else -> "Отправить отчёт"
                        },
                        color = MaterialTheme.colorScheme.surface,
                        fontSize = 14.sp,
                    )
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            color = LocalAppColors.current.muted,
            modifier = Modifier.width(140.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}