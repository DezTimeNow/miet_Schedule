package com.mietschedule.app

import android.content.Context
import android.util.Log
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

/**
 * Обёртка над Appmetrica: одна точка входа для аналитики.
 *
 * Зачем обёртка, а не вызовы SDK прямо в коде:
 *
 * - API-ключ хранится в одном месте: смена приложения в Appmetrica меняет
 *   одну строку, а не инициализацию в каждом экране.
 * - Вызовы не роняют приложение, если аналитика недоступна: весь SDK обёрнут
 *   в try-catch, потому что сбой сбора статистики не должен мешать расписанию.
 * - Инициализация выполняется один раз на процесс. Повторный вызов на каждом
 *   запуске экрана Appmetrica считает новой сессией и завышает метрики.
 * - В событиях не пишется ничего личного: только счёт в игре. Имя игрока в
 *   аналитику не попадает.
 */
object Analytics {

    /**
     * API-ключ приложения в Appmetrica.
     *
     * Настройки → Основное в консоли. Ключ не секретный: он попадает в APK
     * и всё равно доступен тому, кто распаковывает сборку. Секретным является
     * только OAuth-токен для чтения статистики через API.
     */
    private const val API_KEY = "f5ccc94d-8660-4a61-a93d-d5bb0992516d"

    private const val TAG = "Analytics"

    /** Запуск приложения. Считается SDK сам; нужен для проверки подключения. */
    const val EVENT_LAUNCH = "app_launch"

    /** Одно нажатие по микросхеме. */
    const val EVENT_TAP = "game_tap"

    /** Открытие экрана расписания. */
    const val EVENT_SCHEDULE_OPEN = "schedule_open"

    private var started = false

    /**
     * Запускает аналитику. Вызывается один раз из главной Activity.
     *
     * @param context любой контекст; SDK берёт applicationContext сам.
     */
    fun start(context: Context) {
        if (started) return
        val ctx = context.applicationContext
        try {
            val config = AppMetricaConfig.newConfigBuilder(API_KEY).build()
            AppMetrica.activate(ctx, config)
            started = true
            AppMetrica.reportEvent(EVENT_LAUNCH)
            Log.i(TAG, "Appmetrica запущена")
        } catch (t: Throwable) {
            started = false
            Log.w(TAG, "Appmetrica не запустилась: ${t.message}")
        }
    }

    /**
     * Отправляет событие.
     *
     * Не ждёт сети: Appmetrica складывает события в очередь и отправляет сама,
     * поэтому нажатие не задерживается.
     *
     * @param event имя события из констант этого файла.
     */
    fun event(event: String) {
        if (!started) return
        try {
            AppMetrica.reportEvent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "Событие $event не отправлено: ${t.message}")
        }
    }

    /**
     * Отправляет событие с числовым значением.
     *
     * @param event имя события из констант этого файла.
     * @param key   имя параметра, например "score".
     * @param value значение, например текущий счёт.
     */
    fun event(event: String, key: String, value: Int) {
        if (!started) return
        try {
            AppMetrica.reportEvent(event, mapOf(key to value.toString()))
        } catch (t: Throwable) {
            Log.w(TAG, "Событие $event не отправлено: ${t.message}")
        }
    }

    /**
     * Текущий счёт игрока.
     *
     * Отправка не на каждый тап: событий накапливается столько же, сколько
     * нажатий, а счёт меняется медленно. Одного значения за игру достаточно,
     * чтобы увидеть разброс результатов.
     */
    fun reportScore(score: Int) {
        event(EVENT_TAP, "score", score)
    }
}