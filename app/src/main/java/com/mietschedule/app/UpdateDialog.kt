package com.mietschedule.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

import androidx.compose.runtime.Stable


/**
 * Диалог «Доступна новая версия».
 *
 * Отдельный файл, а не кусок в MainActivity: там уже четыре экрана и роутинг,
 * а здесь — собственный состояние (скачивание/ошибка) и своя логика установки.
 * В MainActivity остаётся только вызов [UpdatePromptHost].
 */

/** Состояние диалога обновления. */
enum class UpdateStage { IDLE, DOWNLOADING, ERROR }

/**
 * Мост между экраном «О программе» и диалогом обновления.
 *
 * Диалог рисует [UpdatePromptHost], который живёт уровнем выше (один на всё
 * приложение, он же ловит фоновую проверку при запуске). Экран «О программе»
 * не может обратиться к нему напрямую, поэтому передаёт найденное обновление
 * через эту ручку, а хост подхватывает изменение.
 *
 * [pending] — обычное Compose-состояние, поэтому кнопка в «О программе» и
 * хост видят одно и то же значение.
 */
@Stable
class UpdateDialogHandle {
    var pending by mutableStateOf<UpdateInfo?>(null)
        internal set

    /** Открыть окно обновления для найденного релиза. */
    fun show(info: UpdateInfo) {
        pending = info
    }

    /** Закрыть окно, если оно открыто. */
    fun dismiss() {
        pending = null
    }
}

/**
 * Текст релиза приходит из GitHub как Markdown, где перевод строки — HTML-энтития
 * `&#10;`. Compose рисует такой текст буквально, и пользователь видит «&#10;»
 * вместо разрыва строки. Здесь разворачиваем энтитии в нормальный текст,
 * снимаем Markdown-заголовки и лишние пустые строки.
 */
internal fun cleanReleaseNotes(raw: String): String {
    // Порядок замен важен: `&amp;` разбирается последним. Иначе `&amp;lt;`
    // превратится в `<`, и в тексте релиза окажется символ, которого там нет.
    val decoded = raw
        .replace("&#13;", "")
        .replace("&#10;", "\n")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
    return decoded.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', ' ').trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(1200)
}

/**
 * Проверка при запуске + диалог.
 *
 * Вызывается один раз из MainActivity. Само открытие приложения не ждёт сеть:
 * проверка идёт в фоне, и если новая версия есть — диалог всплывает позже.
 */
@Composable
fun UpdatePromptHost(
    currentVersionCode: Int,
    enabled: Boolean = true,
    onUpdateInfo: (UpdateInfo?) -> Unit = {},
    /**
     * Диалог, который уже открыт снаружи (например, кнопкой «Проверить
     * обновления» в экране «О программе»). Ссылка нужна, чтобы подставить
     * найденное обновление в существующий диалог, а не открывать второй.
     */
    dialogHandle: UpdateDialogHandle? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var stage by remember { mutableStateOf(UpdateStage.IDLE) }
    var errorText by remember { mutableStateOf("") }
    var downloaded by remember { mutableStateOf(-1) }

    // Диалог, открытый снаружи: снимаем ссылку только когда её закрыли,
    // иначе приложение удержит старый UpdateInfo в памяти.
    LaunchedEffect(dialogHandle) {
        val handle = dialogHandle ?: return@LaunchedEffect
        snapshotFlow { handle.pending }
            .collect { info ->
                if (info != null) update = info
            }
    }

    // Фоновая проверка при появлении экрана — один раз за composition.
    LaunchedEffect(enabled, currentVersionCode) {
        if (!enabled) return@LaunchedEffect
        val found = UpdateChecker.checkForUpdate(ctx, currentVersionCode)
        onUpdateInfo(found)
        if (found != null) update = found
    }

    val info = update ?: return

    // Когда окно закрыто (любым способом) — обнуляем и ручку, иначе возврат на
    // экран «О программе» снова подхватит то же обновление и покажет его.
    fun closeDialog() {
        update = null
        stage = UpdateStage.IDLE
        errorText = ""
        downloaded = -1
        dialogHandle?.dismiss()
    }

    AlertDialog(
        onDismissRequest = { if (stage == UpdateStage.IDLE) closeDialog() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MIET_BLUE,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Доступна новая версия",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MIET_BLUE,
                    )
                    Text(
                        "${info.versionLabel}${if (info.sizeLabel.isNotEmpty()) "  ·  ${info.sizeLabel}" else ""}",
                        fontSize = 12.sp,
                        color = Color(0xFF546E7A),
                    )
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 340.dp)) {
                when (stage) {
                    UpdateStage.DOWNLOADING -> {
                        Text(
                            if (downloaded >= 0) "Скачано $downloaded%.1f МБ из ${info.sizeLabel}"
                            else "Скачиваю обновление…",
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        val progress = if (info.sizeBytes > 0 && downloaded >= 0) {
                            (downloaded.toFloat() / info.sizeBytes).coerceIn(0f, 1f)
                        } else null
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MIET_BLUE,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MIET_BLUE,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Не закрывай приложение",
                            fontSize = 12.sp,
                            color = Color(0xFF78909C),
                        )
                    }

                    UpdateStage.ERROR -> {
                        Text("Не получилось скачать обновление", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(errorText, fontSize = 13.sp, color = Color(0xFFB3261E))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Открой релиз в браузере:\n${UpdateChecker.REPO}",
                            fontSize = 12.sp,
                            color = Color(0xFF546E7A),
                        )
                    }

                    UpdateStage.IDLE -> {
                        // Текст релиза — то, что публикуется в release notes,
                        // но через cleanReleaseNotes: GitHub отдаёт переводы
                        // строк как &#10;, и Compose показал бы их буквально.
                        val notes = cleanReleaseNotes(info.notes)
                        if (notes.isNotEmpty()) {
                            Text(
                                notes.take(1200),
                                fontSize = 13.sp,
                                color = Color(0xFF1A1A1A),
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        } else {
                            Text(
                                "Обновление с ${info.versionLabel}",
                                fontSize = 13.sp,
                                color = Color(0xFF1A1A1A),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                UpdateStage.DOWNLOADING -> TextButton(onClick = {}, enabled = false) {
                    Text("Скачиваю…", color = Color(0xFF78909C))
                }

                UpdateStage.ERROR -> Button(
                    onClick = {
                        stage = UpdateStage.IDLE
                        errorText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                ) {
                    Text("Повторить", color = Color.White)
                }

                UpdateStage.IDLE -> {
                    if (!UpdateChecker.canRequestInstall(ctx)) {
                        // Android 8+ без явного разрешения: сначала в настройки,
                        // иначе установщик просто не запустится. Скачивать
                        // в этом случае бессмысленно — сначала разрешение.
                        Button(
                            onClick = {
                                // Настройка «Установка неизвестных приложений»
                                // есть не во всех прошивках и не во всех версиях
                                // Android. Если Activity нет, показываем ошибку в
                                // диалоге, вместо того чтобы уронить приложение.
                                val opened = runCatching {
                                    ctx.startActivity(UpdateChecker.unknownSourcesSettings(ctx))
                                }.isSuccess
                                if (opened) {
                                    closeDialog()
                                } else {
                                    errorText = "Настройка установки недоступна на этом устройстве"
                                    stage = UpdateStage.ERROR
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                        ) {
                            Text("Разрешить установку", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = {
                                stage = UpdateStage.DOWNLOADING
                                downloaded = -1
                                scope.launch {
                                    runCatching {
                                        UpdateChecker.downloadApk(ctx, info)
                                    }.onSuccess { apk ->
                                        // Установщик на этом устройстве может быть
                                        // отключён или отсутствовать (например на
                                        // части прошивок без PackageInstaller).
                                        // Без защиты startActivity падал бы с
                                        // ActivityNotFoundException прямо из
                                        // обработчика результата.
                                        runCatching {
                                            ctx.startActivity(UpdateChecker.installIntent(ctx, apk))
                                            closeDialog()
                                        }.onFailure {
                                            errorText = "Не найдено приложение для установки"
                                            stage = UpdateStage.ERROR
                                        }
                                    }.onFailure { e ->
                                        errorText = e.message ?: "Неизвестная ошибка"
                                        stage = UpdateStage.ERROR
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скачать", color = Color.White)
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (stage != UpdateStage.DOWNLOADING) {
                TextButton(onClick = {
                    // «Позже» — не достаём до следующего релиза.
                    UpdateChecker.recordDismissed(ctx, info.versionCode)
                    closeDialog()
                }) {
                    Text("Позже", color = Color(0xFF78909C))
                }
            }
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.White,
    )
}