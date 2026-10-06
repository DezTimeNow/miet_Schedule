package com.mietschedule.app

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Таблица лидеров мини-игры: чтение топа и отправка результата.
 *
 * ХРАНИЛИЩЕ. Сервера нет. Таблица ведётся скриптом Apps Script, который
 * обращается к приватной таблице Google и развёрнут как веб-приложение
 * с доступом «для всех». Ключей и токенов в приложении нет ни одного.
 *
 * ПОЧЕМУ ИМЕННО GET. Развёрнутый веб-скрипт на POST отвечает 302 и
 * переадресует на script.googleusercontent.com; если следовать за
 * переадресацией, запрос становится GET, а этот адрес его отклоняет
 * с кодом 405. Тело при этом доходит, запись происходит, но ответ
 * приложению теряется — оно не узнаёт своё место в рейтинге. Через GET
 * такой проблемы нет: ответ приходит обычным JSON с кодом 200.
 *
 * ЗАПИСЬ ИДЁТ ЧЕРЕЗ ПАРАМЕТРЫ URL. Это следствие предыдущего пункта: ни
 * один другой способ не возвращает ответ. Побочный эффект — ник и счёт
 * видны в журналах Google и в URL. Для игрового рейтинга это не важно.
 *
 * ГРАНИЦА НЕДЕЛИ считается на стороне скрипта: понедельник, 09:00 по
 * часовому поясу проекта. Приложение свой недельный счёт не ведёт и
 * границу не вычисляет — иначе пришлось бы повторять это правило в двух
 * местах, и расхождение всплыло бы через неделю.
 */
object ChipTop {

    private const val TAG = "ChipTop"

    /**
     * Адрес скрипта.
     *
     * Приватности тут нет: скрипт развёрнут с доступом «для всех»,
     * поэтому адрес извлекается из APK без труда. Секретом он не
     * является — через него можно только читать топ и писать в него,
     * ровно то же, что умеет приложение.
     */
    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbxA2YgoPtiHv3pHyMgUDQeKWhz6kaV9p-xYCQmCZFOKBsOKCtRauDJt3TLjEtlkwqRoSg/exec"

    /** Ник длиннее этого скрипт не принимает. */
    const val NICK_MAX = 30

    /**
     * Потолок счёта на стороне приложения.
     *
     * Скрипт проверяет свои 20000, но приложение не знает, какое там
     * значение стоит сегодня, поэтому держит своё. Расхождение не опасно:
     * при превышении скрипт просто вернёт «score».
     */
    const val SCORE_MAX = 20000

    /**
     * Минимальная пауза между засчитанными тапами, мс.
     *
     * Было 165 мс, то есть шесть тапов в секунду: игрок обгонял других
     * быстрее, чем они успевают нажать. 500 мс — это не чаще двух тапов
     * в секунду, предел скорости человеческой руки: быстрее уже не тапают,
     * а автоматический повтор при удержании обрывается. Без паузы
     * авто-повтор давал сотни очков в секунду, и таблица превращалась
     * в мусор.
     */
    const val MIN_TAP_GAP_MS = 500L

    /**
     * Как часто результат уходит в таблицу во время игры.
     *
     * Замерено curl'ом: один вызов скрипта занимает 2.1–2.6 секунды, и
     * это стабильно. Отправка на каждый тап невозможна — при шести тапах
     * в секунду очередь выросла бы до получаса, а лимит выполнения скрипта
     * (6 минут) срывался бы на 100 очках.
     *
     * Поэтому 3 секунды: это чуть больше времени одного вызова, поэтому
     * запросы не копятся, и игрок видит своё место примерно раз в три
     * нажатия. За минуту игры это около 20 запросов на игрока.
     */
    const val SYNC_INTERVAL_MS = 3_000L

    private const val PREFS = "chip_top"

    /** Ключ сохранённого ника. */
    private const val KEY_NICK = "nick"

    /**
     * Ник, сохранённый на этом устройстве.
     *
     * Хранится локально, потому что человек вводит его один раз и не
     * должен повторять это каждую игру. Пустая строка означает «ещё не
     * задан» — тогда игра сразу просит имя.
     */
    fun savedNick(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NICK, "").orEmpty().trim()

    /** Запомнить ник. Пустое значение забывает его. */
    fun saveNick(ctx: Context, nick: String) {
        val clean = nick.trim().replace(Regex("\\s+"), " ").take(NICK_MAX)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NICK, clean).apply()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Строка таблицы. */
    data class Row(val nick: String, val score: Int)

    /** Топ и срок его действия. */
    data class Top(
        val rows: List<Row>,
        val resetsAtMillis: Long,
        val tz: String,
        val week: Int,
    )

    /** Ответ скрипта на попытку записи. */
    sealed interface SubmitResult {
        /**
         * Записано.
         *
         * [rank] — место в рейтинге, 0 означает «вне топа». [top] приходит
         * вместе с записью: скрипт всё равно читает таблицу целиком, чтобы
         * разобрать позицию, и отдать её в том же ответе стоит ему ничего.
         * Раньше приложение после записи отдельно читало топ, то есть
         * платило по 2.5 секунды дважды за одно и то же обновление.
         */
        data class Saved(
            val rank: Int,
            val total: Int,
            val duplicate: Boolean,
            val top: Top?,
            /**
             * Сколько очков у игрока в таблице по факту.
             *
             * Не «сколько он прислал», а сколько записано. Это и есть точка
             * синхронизации: игрок мог продолжить под чужим ником, и скрипт
             * тогда держит ЛУЧШИЙ счёт из увиденных. Раньше этого поля не
             * было, и клиент оставался со своим счётчиком с нуля, пока
             * в таблице у того же ника стояли чужие очки.
             *
             * Ноль означает «сервер не сообщил» — старый скрипт. Тогда
             * клиент ничего не меняет.
             */
            val score: Int = 0,
        ) : SubmitResult

        /** Отказ скрипта: [reason] — причина в терминах пользователя. */
        data class Rejected(val reason: String) : SubmitResult

        /** Нет связи или скрипт не ответил. */
        data object NoNetwork : SubmitResult
    }

    /**
     * Прочитать текущий топ.
     *
     * Возвращает `null`, если скрипт недоступен: это не «пустой топ», а
     * отсутствие связи, и вызывающий обязан отказать в игре, а не
     * показать пустую таблицу.
     */
    suspend fun loadTop(): Top? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url(ENDPOINT)).get().build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Чтение топа: HTTP ${resp.code} ${body.take(200)}")
                    return@use null
                }
                parseTop(body)
            }
        }.getOrElse {
            Log.w(TAG, "Чтение топа не удалось", it)
            null
        }
    }

    /**
     * Отправить результат игрока.
     *
     * [playerId] — ключ строки игрока, выведенный из ника: скрипт держит в
     * таблице не больше одной строки на него за неделю, поэтому повторная
     * отправка меньшего счёта игнорируется, а большего заменяет прежний.
     *
     * Ключ не привязан к устройству намеренно. Требование владельца:
     * «при смене ника не надо чистить результат, другой человек может
     * сделать такой же ник и продолжить под ним» — значит личность игрока
     * задаёт ник, и каждый ник живёт в своей строке. Смена ника ничего не
     * обнуляет: результат продолжается под новым именем.
     */
    suspend fun submit(nick: String, score: Int, playerId: String): SubmitResult =
        withContext(Dispatchers.IO) {
            val clean = nick.trim().replace(Regex("\\s+"), " ")
            if (clean.isEmpty()) return@withContext SubmitResult.Rejected("Ник не может быть пустым")
            if (clean.length > NICK_MAX) {
                return@withContext SubmitResult.Rejected("Ник длиннее $NICK_MAX символов")
            }
            if (score < 1) return@withContext SubmitResult.Rejected("Счёт не может быть нулевым")
            if (score > SCORE_MAX) return@withContext SubmitResult.Rejected("Счёт выше $SCORE_MAX")

            val target = url(ENDPOINT).newBuilder()
                .addQueryParameter("nick", clean)
                .addQueryParameter("score", score.toString())
                .addQueryParameter("install", playerId)
                .build() ?: return@withContext SubmitResult.NoNetwork

            runCatching {
                val request = Request.Builder().url(target).get().build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Log.i(TAG, "Отправка: HTTP ${resp.code} ${body.take(200)}")
                    if (!resp.isSuccessful) return@use SubmitResult.NoNetwork
                    parseSubmit(body)
                }
            }.getOrElse {
                Log.w(TAG, "Отправка не удалась", it)
                SubmitResult.NoNetwork
            }
        }

    /** Разобрать ответ чтения топа. */
    internal fun parseTop(body: String): Top? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        // Пустой ответ без поля ok — это не «никого нет», а чужая страница
        // на том же адресе. Различать надо именно по ok, иначе поломка скрипта
        // покажет игрокам пустой рейтинг вместо «нужен интернет».
        if (!obj.has("ok") || !obj.get("ok").asBoolean) return null
        val rows = obj.getAsJsonArray("top") ?: return null
        val list = ArrayList<Row>(rows.size())
        for (i in 0 until rows.size()) {
            val e = rows[i].asJsonObject
            val nick = e.get("nick")?.asString ?: continue
            val score = e.get("score")?.asInt ?: continue
            list.add(Row(nick, score))
        }
        // Одна строка на ник, лучший счёт.
        //
        // Развёрнутая версия скрипта при улучшении счёта дописывает новую
        // строку, не убирая прежнюю: в топе появляются два одинаковых ника.
        // На устройстве это выглядит как поломка таблицы, поэтому дубли
        // сворачиваются здесь. Порядок по счёту сохраняется: строка с лучшим
        // счётом стоит там же, где стояла бы она одна.
        val best = LinkedHashMap<String, Int>(list.size)
        for (row in list) {
            val prev = best[row.nick]
            if (prev == null || row.score > prev) best[row.nick] = row.score
        }
        val merged = best.entries
            .sortedByDescending { it.value }
            .map { Row(it.key, it.value) }

        val resets = obj.get("resetsAt")?.asString
        val resetsAt = runCatching {
            java.time.Instant.parse(resets).toEpochMilli()
        }.getOrElse { 0L }
        return Top(
            rows = merged,
            resetsAtMillis = resetsAt,
            tz = obj.get("tz")?.asString.orEmpty(),
            week = obj.get("week")?.asInt ?: 0,
        )
    }

    /** Разобрать ответ записи. */
    internal fun parseSubmit(body: String): SubmitResult {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?: return SubmitResult.NoNetwork
        if (!root.isJsonObject) return SubmitResult.NoNetwork
        val obj = root.asJsonObject
        if (!obj.has("ok")) return SubmitResult.NoNetwork
        if (!obj.get("ok").asBoolean) {
            return SubmitResult.Rejected(when (obj.get("error")?.asString) {
                "nick" -> "Такой ник не принимается"
                "install" -> "Не удалось определить игрока"
                "score" -> "Счёт отклонён таблицей"
                else -> "Таблица отклонила результат"
            })
        }
        return SubmitResult.Saved(
            rank = obj.get("rank")?.asInt ?: 0,
            total = obj.get("total")?.asInt ?: 0,
            duplicate = obj.get("dup")?.asBoolean ?: false,
            // Авторитетный счёт из таблицы. Старый скрипт его не шлёт —
            // тогда остаётся 0 и клиент ничего не подправляет.
            score = obj.get("score")?.asInt ?: 0,
            // Топ лежит в том же объекте, что и результат записи.
            top = parseTop(body),
        )
    }

    private fun url(raw: String) = raw.toHttpUrlOrNull()
        ?: error("Адрес скрипта повреждён")

    /**
     * Ключ строки игрока в таблице.
     *
     * Ключ выводится из ника, а не берётся с устройства. Причина конкретная:
     * скрипт держит одну строку на ключ и не переписывает её, если присланный
     * счёт не больше прежнего. При ключе с устройства игрок, чья строка уже
     * занята чужим результатом, физически не мог попасть в таблицу: отправлял
     * (ник, 13) — скрипт отвечал dup и оставлял прежний ник. Воспроизведено
     * на живом сервере, игрок в таблице не появлялся ни под одним ником.
     *
     * Теперь требование владельца: «при смене ника не надо чистить результат,
     * другой человек может сделать такой же ник и продолжить под ним».
     * Значит личность игрока — это ник, и каждый ник получает свою строку.
     *
     * Смена ника ничего не чистит и не обнуляет: результат продолжается под
     * новым именем, как и требовалось.
     */
    fun playerId(nick: String): String {
        val clean = nick.trim().replace(Regex("\\s+"), " ").lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }
}
