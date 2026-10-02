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
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var stage by remember { mutableStateOf(UpdateStage.IDLE) }
    var errorText by remember { mutableStateOf("") }
    var downloaded by remember { mutableStateOf(-1) }

    // Фоновая проверка при появлении экрана — один раз за composition.
    LaunchedEffect(enabled, currentVersionCode) {
        if (!enabled) return@LaunchedEffect
        val found = UpdateChecker.checkForUpdate(ctx, currentVersionCode)
        onUpdateInfo(found)
        if (found != null) update = found
    }

    val info = update ?: return

    AlertDialog(
        onDismissRequest = { if (stage == UpdateStage.IDLE) update = null },
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
                        // Текст релиза — то, что БОСС пишет в release notes.
                        val notes = info.notes.trim()
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
                                update = null
                                ctx.startActivity(UpdateChecker.unknownSourcesSettings(ctx))
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
                                        ctx.startActivity(UpdateChecker.installIntent(ctx, apk))
                                        update = null
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
                    update = null
                }) {
                    Text("Позже", color = Color(0xFF78909C))
                }
            }
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.White,
    )
}