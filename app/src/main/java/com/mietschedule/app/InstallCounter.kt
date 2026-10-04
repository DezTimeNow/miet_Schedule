package com.mietschedule.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Счётчик установок и возвратов: говорит серверу, что приложение запустили.
 *
 * ЗАЧЕМ ЭТО ОТДЕЛЬНО ОТ ТАБЛИЦЫ ИГРЫ. Игровой скрипт считает очки по
 * нику, и человек, который ни разу не открыл игру, в таблице не появится.
 * Но вопрос владельца другой: «сколько человек установило и сколько
 * реально пользуется». Ответ считается здесь — по устройству, а не по нику.
 *
 * ПОЧЕМУ НЕ APPMETRICA. Appmetrica приписывает установку магазину через
 * install referrer: метку, которую передаёт RuStore или Google Play. При
 * установке руками, скачав APK по ссылке с GitHub, метки нет, и такая
 * установка в отчёте не появляется вообще. Приложение распространяется
 * ссылкой, поэтому реферера не будет ни у кого и цифра всегда была бы
 * нулём. Свой счётчик от магазина не зависит.
 *
 * ЧТО СЧИТАЕТСЯ. Ключ — device, случайный UUID, который приложение создаёт
 * при первом запуске и хранит в SharedPreferences. Он не привязан к
 * железу и не переживает сброс данных: стирая приложение, человек
 * получает новый UUID и засчитывается как новое устройство. Для оценки
 * «сколько людей» это приемлемо и даже точнее: переустановка обычно
 * означает другого человека или того же, но с чистого листа.
 *
 * Счётчики на сервере: devices — сколько разных device пришло хоть раз,
 * returning — сколько из них возвращались, runs — всего запусков.
 */
object InstallCounter {

    private const val TAG = "InstallCounter"

    /**
     * Адрес скрипта.
     *
     * Приватности тут нет: скрипт развёрнут с доступом «для всех», и адрес
     * извлекается из APK без труда. Через него можно только дописать строку
     * в лист Installs и прочитать сводку — ровно то же, что умеет
     * приложение.
     */
    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbxA2YgoPtiHv3pHyMgUDQeKWhz6kaV9p-xYCQmCZFOKBsOKCtRauDJt3TLjEtlkwqRoSg/exec"

    /** Ключи в SharedPreferences. Имя файла не меняем: смена обнулит счёт. */
    private const val PREFS = "chip_install"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_REPORTED = "reported_version"

    /**
     * Как часто повторно сообщать о запуске.
     *
     * Раз в сутки, а не на каждый запуск: за неделю человек открывает
     * приложение десятки раз, и в листе получились бы десятки одинаковых
     * строк одного устройства вместо одной. Для вопроса «сколько людей
     * пользуется» достаточно знать, что устройство живо.
     */
    private const val REPORT_INTERVAL_MS = 24 * 3600 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Сообщает серверу о запуске, если с прошлого раза прошло достаточно
     * времени.
     *
     * Вызывается один раз за запуск приложения и не блокирует старт: сеть
     * здесь синхронная, поэтому вызывающий обязан запускать её из фонового
     * потока. Ошибка молча игнорируется — аналитика не должна влиять на
     * работу приложения.
     *
     * @param versionCode versionCode установленной копии: по нему видно,
     *   на скольких устройствах стоит каждая версия.
     */
    fun reportRun(context: Context, versionCode: Int) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val last = prefs.getLong(KEY_REPORTED, 0L)
        if (now - last < REPORT_INTERVAL_MS) return

        // Отметка ставится до запроса, а не после: если сеть отвалится,
        // повторная попытка при следующем запуске всё равно пройдёт, а вот
        // обратное — приведёт к тому, что устройство будет бить в сервер
        // при каждом старте приложения до конца сессии.
        prefs.edit().putLong(KEY_REPORTED, now).apply()

        val device = deviceId(app)
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("device", device)
            .addQueryParameter("first", if (last == 0L) "true" else "false")
            .addQueryParameter("version", versionCode.toString())
            .addQueryParameter("model", deviceModel())
            .build()

        runCatching {
            client.newCall(Request.Builder().url(url).get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Счётчик установок: HTTP ${resp.code}")
                    }
                }
        }.onFailure {
            Log.w(TAG, "Счётчик установок не отправился", it)
        }
    }

    /**
     * Идентификатор устройства.
     *
     * Создаётся при первом обращении и хранится в SharedPreferences.
     * Формат — UUID без дефисов: скрипт проверяет ключ регулярным выражением
     * и дефисы там не разрешены.
     */
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE, fresh).apply()
        return fresh
    }

    /** Модель телефона: по ней видно, на чём чаще играют. */
    private fun deviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40)
}