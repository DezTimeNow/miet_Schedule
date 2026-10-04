package com.mietschedule.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Мини-игра «Тапай микросхему» и таблица игроков.
 *
 * ОНЛАЙН-РЕЖИМ, БЕЗ КНОПОК. Игра начинается сразу, результат уходит сам.
 * Кнопка отправки убрана намеренно: игрок не должен ничего подтверждать,
 * он должен тапать.
 *
 * ПОЧЕМУ НЕ НА КАЖДЫЙ ТАП. Замерено curl'ом: один вызов скрипта занимает
 * 2.1–2.6 секунды, стабильно. Тап приходит каждые ~165 мс. Отправка на
 * каждый тап копила бы запросы в очереди OkHttp, и они ушли бы пачками
 * спустя десятки секунд после последнего тапа — «реальтайм», при котором
 * игрок видит пустую таблицу. Поэтому синхронизация идёт раз в
 * [ChipTop.SYNC_INTERVAL_MS], всегда последним счётом.
 *
 * СВЕЖЕСТЬ СОСТОЯНИЯ. Раньше цикл синхронизации был `LaunchedEffect(Unit)`
 * и читал `score` и `nick` из замыкания: корутина захватывала их значения
 * на старте — 0 очков и пустой ник — и держала до конца экрана. Из-за
 * этого счёт в таблицу не уходил никогда, а смена ника не давала ничего.
 * Теперь значения читаются через [snapshotFlow], то есть всегда свежие.
 *
 * ТОП ПРИХОДИТ С ЗАПИСЬЮ. Скрипт возвращает его в том же ответе, поэтому
 * игрок сразу видит, кого обогнал, и приложение не тратит на второе
 * обновление ещё 2.5 секунды.
 *
 * НИК ЗАПОМИНАЕТСЯ НА УСТРОЙСТВЕ. Вводится один раз; кнопка «Сменить
 * имя» открывает то же поле. Идентификатор установки не меняется — он
 * служит ключом строки в таблице, и вместе с ним меняется одна строка
 * за неделю.
 *
 * ГРАНИЦА НЕДЕЛИ считается на стороне скрипта, здесь только показывается.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TapChipScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    // ── связь ────────────────────────────────────────────────────────
    var phase by remember { mutableStateOf(Phase.Checking) }
    var top by remember { mutableStateOf<ChipTop.Top?>(null) }
    var rank by remember { mutableIntStateOf(0) }

    // ── ник ──────────────────────────────────────────────────────────
    var nick by remember { mutableStateOf(ChipTop.savedNick(ctx)) }
    var editingNick by remember { mutableStateOf(false) }
    var nickDraft by remember { mutableStateOf(nick) }

    // ── игра ─────────────────────────────────────────────────────────
    var score by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    // ── анимации нажатия ─────────────────────────────────────────────
    // pulse — вспышка, гаснет от 1 до 0 после тапа; press держится, пока
    // палец на корпусе; counterScale подпрыгивает на счётчике.
    val pulse = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val counterScale = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        val loaded = ChipTop.loadTop()
        top = loaded
        phase = if (loaded != null) Phase.Playing else Phase.NoNetwork
        if (nick.isBlank()) editingNick = true
    }

    // Один поток на оба поля. Отдельные snapshotFlow для счёта и ника
    // здесь не годились: значение snapshotFlow доступно только внутри
    // collect, поэтому второй поток в теле цикла прочитать нельзя.
    // Общий поток выдаёт пару и срабатывает на смену ника тоже — раньше
    // переименование игрока ничего в сети не меняло.
    val progressFlow = snapshotFlow { score to nick.trim() }

    /**
     * Автосинхронизация во время игры.
     *
     * Слушает изменения счёта и ника, а не читает их из замыкания. Отправка
     * идёт строго последовательно: пока предыдущий запрос в сети, новый не
     * ставится, поэтому очередь не растёт и каждый запрос несёт актуальный
     * счёт, а не устаревший.
     */
    LaunchedEffect(Unit) {
        var inFlight = false
        progressFlow.collect { (current, name) ->
            // Отправлять имеет смысл только на играющем экране. На экране
            // «Нужен интернет» сети нет, и каждая попытка ждала бы
            // таймаут 8–10 секунд: при таймауте нажатия в секунду это
            // очередь из десятков заведомо бесполезных запросов.
            if (phase != Phase.Playing) return@collect
            if (inFlight || current < 1 || name.isEmpty()) return@collect
            inFlight = true
            try {
                when (val r = ChipTop.submit(name, current, ChipTop.installId(ctx))) {
                    is ChipTop.SubmitResult.Saved -> {
                        rank = r.rank
                        // Новый скрипт отдаёт топ в ответе на запись, и
                        // второй запрос не нужен. Старый его не отдаёт, и
                        // тогда таблица молча замирала бы на том снимке,
                        // что был при открытии экрана. Поэтому при пустом
                        // top делаем отдельное чтение: медленнее, но список
                        // остаётся живым на любой версии скрипта.
                        val fresh = r.top ?: ChipTop.loadTop()
                        fresh?.let { top = it }
                    }
                    else -> Unit
                }
            } finally {
                // Пауза чуть больше времени вызова: скрипт всё равно один на
                // установку, лишние запросы он всё равно отбросил бы как дубли.
                delay(ChipTop.SYNC_INTERVAL_MS)
                inFlight = false
            }
        }
    }

    Scaffold(
        topBar = {
            MietTopBar(
                title = "Тапать микросхему",
                subtitle = "Расписание МИЭТ",
                onRefresh = onBack,
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                Phase.Checking -> {
                    Spacer(Modifier.height(40.dp))
                    CircularProgressIndicator(color = MIET_BLUE)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onBack) {
                        Text("Вернуться в меню", fontSize = 14.sp)
                    }
                }

                Phase.NoNetwork -> {
                    Spacer(Modifier.height(24.dp))
                    NoNetworkCard(
                        onRetry = {
                            phase = Phase.Checking
                            scope.launch {
                                val loaded = ChipTop.loadTop()
                                top = loaded
                                phase = if (loaded != null) Phase.Playing else Phase.NoNetwork
                            }
                        },
                        onBack = onBack,
                    )
                }

                Phase.Playing -> {
                    // ── счётчик ───────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Счётчик сам подпрыгивает при тапе: Animatable
                        // меняет кегль, а не рисует текст заново.
                        Text(
                            "$score",
                            fontSize = (46f * counterScale.value).sp,
                            fontWeight = FontWeight.Bold,
                            color = MIET_BLUE,
                        )
                        if (rank > 0) {
                            Text(
                                "  место ${'$'}rank",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalAppColors.current.muted,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── микросхема ────────────────────────────────────
                    // Цвета читаются обычными val: обращаться к
                    // LocalAppColors.current и MaterialTheme внутри
                    // remember нельзя — это @Composable-вызовы.
                    val bodyTop = LocalAppColors.current.chipBody
                    val bodyDeep = LocalAppColors.current.chipBodyDeep
                    val pinCol = LocalAppColors.current.chipPin
                    val silk = MaterialTheme.colorScheme.onSurface
                    val art = remember(bodyTop, bodyDeep, pinCol, silk) {
                        ChipArt(
                            measurer = measurer,
                            bodyTop = bodyTop,
                            bodyBottom = bodyDeep,
                            pinColor = pinCol,
                            silkscreen = silk,
                            highlight = MIET_BLUE,
                        )
                    }
                    val glow = pulse.value
                    val pressAmt = press.value
                    val tapScore = score

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.12f)
                    ) {
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            press.animateTo(1f, tween(90))
                                            tryAwaitRelease()
                                            press.animateTo(0f, tween(160))
                                        },
                                        onTap = {
                                            val now = System.currentTimeMillis()
                                            // Пауза между тапами: без неё
                                            // авто-повтор при удержании
                                            // даёт сотни очков в секунду.
                                            if (now - lastTapAt < ChipTop.MIN_TAP_GAP_MS) {
                                                return@detectTapGestures
                                            }
                                            lastTapAt = now
                                            score += 1
                                            // Вспышка при тапе: 1 → 0 за 380 мс.
                                            // animatable-функции suspend, а
                                            // onTap — обычный обработчик, поэтому
                                            // запуск отдельный.
                                            scope.launch {
                                                pulse.snapTo(1f)
                                                pulse.animateTo(0f, tween(380))
                                                counterScale.snapTo(1.18f)
                                                counterScale.animateTo(1f, tween(220))
                                            }
                                        },
                                    )
                                }
                        ) {
                            // art.draw — расширение DrawScope, поэтому
                            // приёмник должен быть видим как such.
                            with(art) {
                                draw(
                                    glow = glow,
                                    pulse = glow,
                                    press = pressAmt,
                                    chipLabel = ChipArt.CHIP_LABEL,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    TapButton(
                        enabled = nick.isNotBlank(),
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - lastTapAt < ChipTop.MIN_TAP_GAP_MS) return@TapButton
                            lastTapAt = now
                            score += 1
                            scope.launch {
                                pulse.snapTo(1f)
                                pulse.animateTo(0f, tween(380))
                                counterScale.snapTo(1.18f)
                                counterScale.animateTo(1f, tween(220))
                            }
                        },
                    )

                    Spacer(Modifier.height(6.dp))

                    // ── ник ──────────────────────────────────────────
                    NickRow(
                        nick = nick,
                        onChange = { editingNick = true },
                    )

                    Spacer(Modifier.height(20.dp))
                    Leaderboard(top)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // ── диалог ника ──────────────────────────────────────────────────
    if (editingNick) {
        NickDialog(
            initial = nickDraft,
            onDismiss = {
                editingNick = false
            },
            onSave = { value ->
                val renamed = nickDraft.trim().isNotEmpty() &&
                    value.trim() != nick.trim()
                nickDraft = value
                ChipTop.saveNick(ctx, value)
                nick = value
                editingNick = false
                // Смена имени = новый игрок. Счёт обнуляется на устройстве
                // и строка удаляется из таблицы: иначе человек переименовывался,
                // набирал очки и оставался в топе под прежним именем, не
                // находя себя. Раньше здесь отправлялся счёт 0, скрипт его
                // отклонял, и ничего не менялось.
                if (renamed) {
                    score = 0
                    lastTapAt = 0L
                    rank = 0
                    scope.launch {
                        when (val r = ChipTop.reset(value, ChipTop.installId(ctx))) {
                            is ChipTop.SubmitResult.Saved -> r.top?.let { top = it }
                            else -> {
                                // Скрипт мог быть ещё старым и не знать про
                                // сброс. Тогда читаем топ обычным запросом,
                                // чтобы список не остался пустым.
                                ChipTop.loadTop()?.let { top = it }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Что происходит с игрой. */
private enum class Phase { Checking, NoNetwork, Playing }

/**
 * Кнопка тапа под корпусом микросхемы.
 *
 * ПОЧЕМУ НЕ МЕНЯЕТСЯ LAYOUT. Первая версия анимировала масштаб через
 * graphicsLayer на самом Box. Нажатие сдвигало кнопку, а вместе с ней
 * приподнимался и «Топ игроков» — интерфейс дёргался. Здесь масштабируется
 * только содержимое (подпись и блик), а рамка остаётся неподвижной: её
 * размер задаётся Modifier, и она не участвует в анимации.
 *
 * НАЖАТИЕ. Кадр при нажатии — мгновенное затемнение фона без смещения.
 * Пружинного возврата нет: на быстрых тапах он читался как дрожание.
 */
@Composable
private fun TapButton(onClick: () -> Unit, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Пульсация только цветом рамки: масштаб и высота не трогаются, поэтому
    // соседние элементы не сдвигаются. Период 1.6 с — достаточно медленно,
    // чтобы не мешать игре и не отвлекать от счётчика.
    val glow = rememberInfiniteTransition(label = "tapGlow")
    val glowValue by glow.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tapGlowValue",
    )

    val base = if (pressed) MIET_BLUE else MIET_BLUE.copy(alpha = 0.72f + 0.28f * glowValue)

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    // Рамка светится по краю, корпус кнопки остаётся ровным.
                    Brush.linearGradient(
                        listOf(
                            MIET_BLUE.copy(alpha = 0.35f * glowValue),
                            MIET_BLUE.copy(alpha = 0.08f * glowValue),
                        ),
                    ),
                )
                .padding(3.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(base)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Подпись. Сдвиг при нажатии — в пределах кнопки, поэтому
            // содержимое страницы не двигается.
            Text(
                text = GAME_TITLE,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.graphicsLayer {
                    translationY = if (pressed) 1f else 0f
                    alpha = if (pressed) 0.85f else 1f
                },
            )
        }
    }
}

/** Имя игрока и кнопка смены. */
@Composable
private fun NickRow(nick: String, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (nick.isBlank()) {
            Text(
                "Имя не задано",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                nick,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onChange) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Сменить имя",
                tint = MIET_BLUE,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Экран «нет связи». Игра не запускается и ничего не сохраняет. */
@Composable
private fun NoNetworkCard(onRetry: () -> Unit, onBack: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.errorContainer),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Нужен интернет",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Игра и таблица игроков работают через интернет.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                    Text("Повторить", fontSize = 14.sp)
                }
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                ) {
                    Text("В меню", fontSize = 14.sp)
                }
            }
        }
    }
}

/** Диалог ввода ника. */
@Composable
private fun NickDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial.take(ChipTop.NICK_MAX)) }
    val trimmed = value.trim().replace(Regex("\\s+"), " ")
    val fits = trimmed.length <= ChipTop.NICK_MAX

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ваше имя", fontSize = 17.sp) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= ChipTop.NICK_MAX + 8) value = it },
                singleLine = true,
                label = { Text("До ${ChipTop.NICK_MAX} символов", fontSize = 13.sp) },
                supportingText = {
                    Text("${trimmed.length} / ${ChipTop.NICK_MAX}", fontSize = 11.sp)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = trimmed.isNotEmpty() && fits,
            ) {
                Text("Сохранить", fontSize = 14.sp, color = MIET_BLUE)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", fontSize = 14.sp, color = LocalAppColors.current.muted)
            }
        },
    )
}

/**
 * Таблица игроков.
 *
 * Своя строка помечается: без неё игрок в пятидесяти строках ищет себя
 * глазами. Место хранится в `rank` — оно приходит ответом скрипта.
 */
@Composable
private fun Leaderboard(top: ChipTop.Top?) {
    val list = top?.rows ?: return
    Text(
        "Топ игроков",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))

    if (list.isEmpty()) {
        Text(
            "Пока пусто. Будь первым.",
            fontSize = 13.sp,
            color = LocalAppColors.current.muted,
        )
        return
    }

    // Своя строка: сверяем по нику. Точное совпадение, а не «похожее»:
    // иначе «Аня» подсветит строку «Аня2».
    val ownNick = ChipTop.savedNick(LocalContext.current)
    list.forEachIndexed { i, row ->
        val mine = ownNick.isNotBlank() && row.nick == ownNick
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .then(
                    if (mine) Modifier.background(LocalAppColors.current.currentGroup)
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${i + 1}",
                fontSize = 13.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.width(34.dp),
            )
            Text(
                row.nick,
                fontSize = 14.sp,
                fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                color = if (mine) MIET_BLUE else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                row.score.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MIET_BLUE,
            )
        }
    }
}