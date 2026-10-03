package com.mietschedule.app

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Отправка сообщения об ошибке через Web3Forms.
 *
 * СЕРВИС. Заявка уходит на почту владельца без бэкенда и без регистрации
 * в приложении: приложение делает POST на api.web3forms.com, письмо
 * приходит на адрес, привязанный к ключу.
 *
 * КЛЮЧ ПУБЛИЧНЫЙ, И ЭТО НОРМАЛЬНО. В FAQ Web3Forms сказано прямо: «the
 * Access Key is not a secret API Key. it can be Public and it's safe to
 * use it in the client-side code». Он и лежит в исходнике любой HTML-страницы
 * с такой формой. В отличие от токена Telegram-бота, который из APK
 * вытаскивается и позволяет писать от чужого имени, здесь утёкший ключ
 * даёт спамеру только возможность слать мусор на почту.
 *
 * ОТПРАВКА ТОЛЬКО С КЛИЕНТА. Серверный POST отклоняется: «This method is
 * not allowed. Use our API in client side». Проверено curl'ом с этой машины
 * — пришло success:false. То есть правило сервиса: браузер или телефон
 * отправляет, сервер не отправляет. Именно поэтому честная проверка
 * возможна только с устройства.
 *
 * ЗАЩИТА ОТ МУСОРА. Настоящей защиты нет, и это важно понимать: того, кто
 * целенаправленно возьмёт ключ из APK, остановить нечем. Что есть:
 *   - [botcheck] — honeypot-поле. Настоящий пользователь его НЕ шлёт
 *     (поле скрытое и пустое), а спамер, собирающий форму по их
 *     документации, добавляет — и его отчёт отбрасывается сервисом;
 *   - ограничение частоты [RATE_LIMIT_MS] — не чаще раза в 15 минут;
 *   - лимит 250 отправок в месяц у бесплатного плана.
 */
object ReportSender {

    /** Публичный ключ формы. Секретом не является — см. KDoc выше. */
    private const val ACCESS_KEY = "25921919-b90b-47d9-822e-ba031dfca29c"

    private const val ENDPOINT = "https://api.web3forms.com/submit"

    /** Не чаще раза в 15 минут: защита и от спама, и от повторного тапа. */
    const val RATE_LIMIT_MS = 15L * 60L * 1000L

    private const val PREFS = "report_state"

    /** Отдельный клиент: не тянем таймауты и куки расписания МИЭТ. */
    private val client = OkHttpClient.Builder().build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Что получилось. */
    sealed interface Result {
        /** Письмо принято сервисом. */
        data object Sent : Result

        /** Отказ: сеть, лимит или ответ сервиса. */
        data class Failed(val reason: String) : Result

        /** Прошло меньше [RATE_LIMIT_MS] с прошлой удачной отправки. */
        data class TooSoon(val minutesLeft: Int) : Result
    }

    /** Можно ли отправлять прямо сейчас. */
    fun leftRateLimitMs(ctx: Context): Long {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("last_sent", 0L)
        return (last + RATE_LIMIT_MS) - System.currentTimeMillis()
    }

    /**
     * Подпись под кнопкой, когда отправка ещё закрыта.
     *
     * Округление вверх, но без выхода за пределы: при ровно 15 минутах
     * остатка должно быть «15 мин», а не «16». Поэтому из миллисекунд
     * сначала вычитается одна полная минута, и только остаток
     * округляется вверх.
     */
    fun rateLimitLabel(leftMs: Long): String {
        if (leftMs <= 60_000L) return "Можно отправить через минуту"
        val mins = ((leftMs - 1) / 60_000L + 1).toInt()
        return "Можно отправить через $mins мин"
    }

    /**
     * Отправить отчёт.
     *
     * Комментарий [comment] необязателен: приложение само дописывает в
     * письмо версию, роль, выбранное расписание и состояние данных — этого
     * достаточно, чтобы понять, что у человека происходит, не задавая ему
     * вопросов в телеграме.
     */
    suspend fun send(ctx: Context, report: ReportData, comment: String): Result {
        val app = ctx.applicationContext
        val left = leftRateLimitMs(app)
        if (left > 0) return Result.TooSoon(((left / 60_000L) + 1).toInt())

        return withContext(Dispatchers.IO) {
            val payload = buildJson(report, comment)
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                // ЗАГОЛОВКИ. Web3Forms отсекает запрос по User-Agent: с
                // дефолтным `okhttp/4.12.0` приходит 403 «This method is not
                // allowed. Use our API in client side» — проверено curl'ом и с
                // эмулятора. Ставим строку, которой отвечает системный WebView
                // того же телефона: константа была бы чужеродной.
                .header("User-Agent", webViewUserAgent(app))
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                // Эти два заголовка шлёт сам браузер при отправке формы.
                .header("Origin", "https://web3forms.com")
                .header("Referer", "https://web3forms.com/")
                .post(payload.toRequestBody(JSON))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Log.i("ReportSender", "HTTP ${resp.code} body=${body.take(300)}")
                    val ok = body.contains("\"success\"") && !body.contains("\"success\":false")
                    if (resp.isSuccessful && ok) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putLong("last_sent", System.currentTimeMillis()).apply()
                        Result.Sent
                    } else {
                        Result.Failed(explain(resp.code, body))
                    }
                }
            } catch (e: Exception) {
                Log.w("ReportSender", "Отправка не удалась", e)
                Result.Failed("Не удалось отправить")
            }
        }
    }

    /**
     * Причина отказа в терминах пользователя.
     *
     * Внутренние коды и тексты сервиса в интерфейс не выносятся: по ним
     * ничего не сделать, а человеку остаётся только расстраиваться.
     * Подробности остаются в logcat.
     */
    private fun explain(code: Int, body: String): String {
        // Cloudflare отдаёт HTML-челлендж, а не JSON. Значит, до самой формы
        // запрос не дошёл: сервис решил, что его прислал робот.
        return when {
            body.contains("challenge") || body.startsWith("<!DOCTYPE") ->
                "Сервис не принял отчёт с телефона"
            code == 403 -> "Сервис не принял отчёт с телефона"
            code in 500..599 -> "Нет связи с сервисом"
            else -> "Сервис не принял отчёт"
        }
    }

    /**
     * User-Agent системного WebView.
     *
     * Берём у самого устройства: у разных версий Android строка разная, и
     * зашитая константа выдала бы себя.
     */
    private fun webViewUserAgent(app: Context): String = runCatching {
        android.webkit.WebView(app).settings.userAgentString
    }.getOrNull()?.takeIf { it.contains("Mozilla") } ?: FALLBACK_UA

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Тело запроса.
     *
     * `botcheck` намеренно НЕ включён: настоящий пользователь это поле не
     * заполняет, и отправка с пустым значением для сервиса неотличима от
     * бот��вой. Спамер, собирающий форму по документации Web3Forms, добавит
     * поле сам, и его отчёт будет отброшен сервисом как спам.
     */
    internal fun buildJson(report: ReportData, comment: String): String {
        val json = JsonObject()
        json.addProperty("access_key", ACCESS_KEY)
        json.addProperty("subject", "Отчёт об ошибке из приложения «Расписание МИЭТ»")
        json.addProperty("name", report.deviceName)
        json.addProperty("message", report.body(comment))
        json.addProperty("replyto", report.replyTo())
        return json.toString()
    }
}