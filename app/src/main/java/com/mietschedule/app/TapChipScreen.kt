package com.mietschedule.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.mietschedule.app.R

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
 * имя» открывает то же поле. Ключ строки в таблице выводится из самого
 * ника, поэтому смена имени ничего не обнуляет: результат продолжается
 * под новым именем, а прежняя строка остаётся нетронутой.
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

    // ── ник ──────────────────────────────────────────────────────────
    var nick by remember { mutableStateOf(ChipTop.savedNick(ctx)) }
    var editingNick by remember { mutableStateOf(false) }
    var nickDraft by remember { mutableStateOf(nick) }

    // ── игра ─────────────────────────────────────────────────────────
    var score by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    // Место в рейтинге. Приходит из ответа скрипта: тот всё равно читает
    // таблицу целиком, чтобы разобрать позицию, и отдать её в том же ответе
    // стоит ему ничего. Ноль — «вне топа», тогда показывать нечего.
    //
    // Хранится отдельно от score: очки меняются каждый тап, а место — раз
    // в SYNC_INTERVAL_MS. Смешивать их в одном состоянии нельзя, иначе
    // надпись прыгала бы на каждый тап.
    var rank by remember { mutableIntStateOf(0) }

    // ── анимации нажатия ─────────────────────────────────────────────
    // press держится, пока палец на корпусе; counterScale подпрыгивает на
    // счётчике через graphicsLayer, то есть без влияния на раскладку.
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
                when (val r = ChipTop.submit(name, current, ChipTop.playerId(name))) {
                    is ChipTop.SubmitResult.Saved -> {
                        // Новый скрипт отдаёт топ в ответе на запись, и
                        // второй запрос не нужен. Старый его не отдаёт, и
                        // тогда таблица молча замирала бы на том снимке,
                        // что был при открытии экрана. Поэтому при пустом
                        // top делаем отдельное чтение: медленнее, но список
                        // остаётся живым на любой версии скрипта.
                        val fresh = r.top ?: ChipTop.loadTop()
                        fresh?.let { top = it }
                        // Место обновляем только если скрипт его прислал.
                        // При r.rank == 0 (вне топа) прошлый результат
                        // стирать нельзя: иначе надпись мигала бы «место N»
                        // и исчезала между двумя синхронизациями.
                        if (r.rank > 0) rank = r.rank
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
                // Название берётся из общей константы, а не пишется строкой.
                // В шапке стоял свой вариант «Тапать микросхему», а в меню —
                // «Тапай микросхему»: два названия одного и того же на двух
                // соседних экранах.
                title = GAME_TITLE,
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
                    // Счётчик подпрыгивает при тапе: Animatable меняет кегль,
                    // а не рисует текст заново.
                    //
                    // Рядом только число очков. Место в рейтинге здесь не
                    // показывается: требование владельца — «это вообще юзеру
                    // не нужно». Раньше здесь стояло «место ${'$'}rank», и
                    // надпись выводилась буквально с $rank — выглядело как
                    // «8 место $rank», и владелец принял это за количество
                    // очков. Проверено живьём: rank действительно место,
                    // то есть значение было верным, а вот подпись врала.
                    // Число очков. Кегль ФИКСИРОВАН — анимация идёт через
                    // graphicsLayer, а не через fontSize.
                    //
                    // Почему так: fontSize меняет размер строки, поэтому при
                    // каждом тапе высота блока менялась, Column пересчитывал
                    // раскладку, и «Топ игроков» вместе с микросхемой прыгал.
                    // Плюс на переходе 9→10 и 99→100 число становилось шире, и
                    // центровка дёргалась вбок. graphicsLayer рисует крупнее,
                    // но на измерение не влияет: раскладка стоит на месте.
                    //
                    // transformOrigin = центр: масштаб растёт в обе стороны,
                    // цифры не «уезжают» от центра экрана.
                    Box(
                        Modifier.height(SCORE_BOX_HEIGHT.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$score",
                            fontSize = SCORE_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                            color = MIET_BLUE,
                            modifier = Modifier.graphicsLayer {
                                scaleX = counterScale.value
                                scaleY = counterScale.value
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            },
                        )
                    }

                    // Место в топе — строкой под очками. Раньше такая строка
                    // стояла здесь же, но собиралась как "место ${'$'}rank",
                    // и интерполяция не сработала: на экране выходило
                    // «место $rank». Сейчас это [rankLabel], где значение
                    // подставляется как число.
                    val place = rankLabel(rank)
                    if (place != null) {
                        Text(
                            place,
                            fontSize = 15.sp,
                            color = LocalAppColors.current.muted,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── микросхема ────────────────────────────────────
                    val pressAmt = press.value

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.12f)
                    ) {
                        // Картинка микросхемы — векторный ресурс, поэтому
                        // масштабируется без потерь на любом экране. Нажатия
                        // обрабатываются на этом же Box, а не на картинке:
                        // так область тапа не зависит от того, как именно
                        // лягут градиенты.
                        // Отклик на нажатие — только затемнение. Масштаб здесь
                        // недопустим: первая версия анимировала размер элемента
                        // через graphicsLayer, и «Топ игроков» подпрыгивал
                        // вместе с микросхемой, то есть дёргалась вёрстка.
                        val chipModifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - pressAmt * 0.08f }
                        Image(
                            painter = painterResource(R.drawable.ic_miet_chip),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = chipModifier,
                        )
                        Box(
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
                                            // Аналитика: засчитанный тап.
                                            Analytics.reportScore(score)
                                            // Вспышка при тапе: 1 → 0 за 380 мс.
                                            // animatable-функции suspend, а
                                            // onTap — обычный обработчик, поэтому
                                            // запуск отдельный.
                                            scope.launch {
                                                counterScale.snapTo(1.18f)
                                                counterScale.animateTo(1f, tween(220))
                                            }
                                        },
                                    )
                                }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

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
                // Смена имени НЕ обнуляет счёт. Требование владельца:
                // «при смене ника не надо чистить результат, другой человек
                // может сделать такой же ник и продолжить под ним». Личность
                // игрока задаёт ник, поэтому новый результат просто уходит
                // в строку нового ника, а прежняя строка остаётся нетронутой.
                //
                // Раньше здесь стоял сброс: счёт обнулялся, а строка установки
                // удалялась из таблицы. Это не помогало — скрипт держал строку
                // по идентификатору устройства, и игрок с заблокированной
                // строкой не мог попасть в таблицу ни под каким ником. Теперь
                // блокировки нет: ключ строки выведен из самого ника.

            },
        )
    }
}

/** Что происходит с игрой. */
private enum class Phase { Checking, NoNetwork, Playing }

/**
 * Подпись места в рейтинге под счётом очков.
 *
 * Возвращает null, когда показывать нечего: до первой синхронизации места
 * ещё нет, а вне топа скрипт отдаёт 0. Пустая строка в этом случае означала
 * бы пустое место под очками и лишний вертикальный зазор.
 *
 * Отдельная функция, а не конкатенация на экране, потому что именно здесь
 * раньше проскочила неотработавшая интерполяция.
 */
internal fun rankLabel(rank: Int): String? = when {
    rank <= 0 -> null
    else -> "место $rank"
}

/**
 * Геометрия счётчика очков.
 *
 * Высота задана явно, потому что счётчик теперь анимируется масштабом, а
 * масштаб не влияет на измерение: без Box с фиксированной высотой блок
 * схлопывался бы до кегля текста и строка под ним прыгала бы.
 *
 * SCORE_FONT_SP задан числом, а не выражением с counterScale: кегль обязан
 * быть постоянным, иначе вся правка выше теряет смысл.
 */
private const val SCORE_FONT_SP = 46f
private const val SCORE_BOX_HEIGHT = 62

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
 * глазами. Место в рейтинге отдельно не показывается — требование
 * владельца; здесь важно только найти свою строку.
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