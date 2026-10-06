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
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.rememberCoroutineScope

import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


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
    onReport: () -> Unit = {},
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
                colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.currentGroup),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Расписание МИЭТ", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Версия $versionName (сборка $versionCode)",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ───────── кнопка «Проверить обновления» ─────────
            // П.6: стоит ПЕРВОЙ, сразу под карточкой с версией. Раньше она
            // была после пяти блоков описания, то есть на телефоне её
            // приходилось выкатывать вниз; на узком экране — прокручивать.
            // Это проверка обновлений приложения: она спрашивает GitHub
            // напрямую, минуя 6-часовой кэш, поэтому кнопка ⭯ в шапке
            // (перезагрузка расписания) тут не годится — это другое.
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
                                "Установлена свежая версия (на GitHub ${result.tagName})"
                            is UpdateCheckResult.Failed -> checkMessage = result.reason
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MIET_BLUE,
                    disabledContainerColor = LocalAppColors.current.currentGroup,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
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
                    color = MaterialTheme.colorScheme.surface,
                    fontSize = 14.sp,
                )
            }

            checkMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ───────── кнопка «Сообщить об ошибке» ─────────
            // Стоит сразу под проверкой обновлений: обе кнопки про то, как
            // приложение себя ведёт. Дальше идёт описание работы — его
            // читают, когда хотят понять приложение, а не починить его.
            Button(
                onClick = onReport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Сообщить об ошибке",
                    color = MaterialTheme.colorScheme.surface,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                lastCheckLabel(lastChecked),
                fontSize = 12.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(4.dp))

            // Напоминания и выбор темы перенесены в «Настройки»:
            // их ищут перед использованием, а «О программе» открывают,
            // когда хотят почитать о приложении. Доступ — с главного экрана.

            // Описание переписано под интерфейс 0.65–0.66 по требованию
            // владельца: блок на главной теперь называется «Ближайшие пары»,
            // избранное живёт списком кнопок, а добавление — кнопкой
            // «Добавить». Прежний текст описывал экраны, которых уже нет.
            AboutBlock(
                "Ближайшие пары",
                "На главной видно, что идёт сейчас и что дальше: предмет, " +
                "аудитория, преподаватель и через сколько начнётся. " +
                "Если пара идёт, так и написано. Отсчёт обновляется сам."
            )

            AboutBlock(
                "Избранное",
                "Группа, преподаватель или аудитория отмечаются звездой и " +
                "появляются на главной списком. Нажатие открывает расписание " +
                "сразу. Добавить — кнопкой «Добавить»."
            )

            AboutBlock(
                "Работает без интернета",
                "Расписание загружается с сайта МИЭТ и хранится на телефоне. " +
                "Без сети показывается то, что уже сохранено."
            )

            AboutBlock(
                "Напоминания",
                "За 1, 5, 10 или 15 минут до начала пары. Включаются в " +
                "«Настройках» и работают без интернета."
            )

            AboutBlock(
                "Мини-игра «Тапать микросхему»",
                "Нажимайте прямо по микросхеме — счёт идёт в таблицу " +
                "игроков, она обновляется сама. Чтобы результат попал " +
                "в таблицу, укажите имя; вернувшись под тем же ником, " +
                "продолжите с набранного. Сброс по понедельникам " +
                "в 09:00. Нужен интернет: результат должен быть настоящим."
            )

            AboutBlock(
                "Обновления",
                "Приложение само проверяет на GitHub, не вышел ли новый " +
                "релиз. Скачанный файл сверяется по контрольной сумме — " +
                "битый или подменённый не установится."
            )

            // Блок про аналитику. Требование от 0.68: в интерфейсе не было
            // ни слова о том, что приложение отправляет. Текст сверен с
            // Analytics.kt, InstallCounter.kt и ReportData.kt: в статистику
            // не попадает ни имя игрока, ни название группы.
            AboutBlock(
                "Аналитика",
                "Собирается обезличенная статистика: запуск, открытое " +
                "расписание и счёт в мини-игре. Имя игрока в неё не " +
                "попадает. Установки считаются по случайному номеру " +
                "устройства и модели телефона. Сообщение об ошибке уходит " +
                "только по вашей команде."
            )

            // ───────── исходный код ─────────
            //
            // Требование владельца от 0.66: «в о программе добавить ссылку на
            // гитхаб». Адрес берётся из UpdateChecker.REPO — того же места,
            // откуда приложение спрашивает релизы, чтобы адрес не разъехался
            // между двумя строками.
            TextButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/" + UpdateChecker.REPO),
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    tint = MIET_BLUE,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Исходный код на GitHub", color = MIET_BLUE, fontSize = 13.sp)
            }


            Spacer(Modifier.height(6.dp))


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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MIET_BLUE)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
