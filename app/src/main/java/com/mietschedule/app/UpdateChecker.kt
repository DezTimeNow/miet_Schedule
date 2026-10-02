package com.mietschedule.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Проверка новых версий приложения через GitHub Releases.
 *
 * Зачем так сложно: приложение живёт вне магазина (RuStore обновляет не каждый
 * день), поэтому единственный способ доставить новую сборку — самому приложению.
 * Пользователь должен один раз нажать «Скачать», дальше Android ставит пакет.
 *
 * Что здесь важно знать:
 *  - проверка НЕ выполняется при каждом старте вплотную: GitHub отдаёт 60
 *    запросов в час на IP, и приложение, дёргающее API на каждом запуске, выжигает
 *    лимит за несколько дней. Поэтому результат кэшируется на [CHECK_INTERVAL_MS];
 *  - отказ пользователя («Позже») запоминается до появления следующего релиза —
 *    иначе диалог доставал бы при каждом запуске;
 *  - скачанный APK сверяется по sha256 из поля `digest` GitHub: битый или
 *    подменённый файл не установится;
 *  - `REQUEST_INSTALL_PACKAGES` в манифесте обязателен: без него Android не даст
 *    запустить установку пакета из файла, а `file://` на Android 8+ запрещён,
 *    поэтому отдаём `content://` через FileProvider.
 */
object UpdateChecker {

    /** Репозиторий с релизами. Публичный: без токена ссылка отдаёт 404. */
    const val REPO = "DezTimeNow/miet_Schedule"

    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** 6 часов — компромисс между «узнать быстро» и «не выжечь лимит GitHub». */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val PREFS = "update_state"
    private const val K_LAST_CHECK = "last_check_ms"
    private const val K_LAST_SEEN = "last_seen_version"
    private const val K_DISMISSED = "dismissed_version"

    /** Интернет-запрос и файлы — всегда вне главного потока. */
    suspend fun checkForUpdate(
        ctx: Context,
        currentVersionCode: Int,
        force: Boolean = false,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = prefs(ctx)
        val lastCheck = prefs.getLong(K_LAST_CHECK, 0L)
        if (!force && lastCheck > 0 && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            return@withContext null // недавно проверяли — не дёргаем GitHub зря
        }
        prefs.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply()

        val info = fetchLatest(API_LATEST) ?: return@withContext null
        // Метка релизов может быть «v12», «12» или «release-12» — берём число.
        val latest = parseVersionCode(info.tagName)
        if (latest == null || latest <= currentVersionCode) return@withContext null
        // Пользователь уже отказался от этой версии — не достаём повторно.
        if (prefs.getInt(K_DISMISSED, -1) == latest) return@withContext null
        info
    }

    /**
     * Проверка по кнопке «Проверить обновления»: без 6-часового кэша и без учёта
     * отказа. Пользователь спросил — отвечаем честно, что на самом деле есть
     * на GitHub, даже если он это обновление уже откладывал.
     *
     * [checkForUpdate] для фоновой проверки при запуске отличается: там кэш и
     * отказ важны, чтобы не доставать диалогом. Здесь результат всегда свежий.
     */
    suspend fun checkNow(ctx: Context, currentVersionCode: Int): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            prefs(ctx).edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply()

            val info = fetchLatest(API_LATEST)
                ?: return@withContext UpdateCheckResult.Failed(
                    "GitHub не ответил. Проверь интернет и попробуй ещё раз."
                )
            val latest = parseVersionCode(info.tagName)
                ?: return@withContext UpdateCheckResult.Failed(
                    "Не удалось разобрать номер версии из тега «${info.tagName}»."
                )
            if (latest <= currentVersionCode) {
                UpdateCheckResult.UpToDate(latest, info.tagName)
            } else {
                UpdateCheckResult.Available(info)
            }
        }

    /** Скачивание APK релиза с проверкой sha256. Бросает исключение при несовпадении. */
    suspend fun downloadApk(ctx: Context, update: UpdateInfo): File = withContext(Dispatchers.IO) {
        // Кладём в cache/updates — этот путь объявлен в res/xml/file_paths.xml.
        // Внешнее хранилище специально не используем: на Android 11+ доступ к
        // общей папке Download из приложения ограничен, и FileProvider такое
        // содержимое всё равно не отдал бы наружу без лишних прав.
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, update.apkName)
        val tmp = File(apk.parentFile, apk.name + ".part")
        tmp.outputStream().use { out ->
            http.newCall(
                okhttp3.Request.Builder().url(update.downloadUrl).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body!!.byteStream().use { it.copyTo(out) }
            }
        }
        update.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = sha256(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                tmp.delete()
                error("Контрольная сумма не сошлась")
            }
        }
        tmp.renameTo(apk)
        apk
    }

    /**
     * Запуск установки. Android 8+ запрещает `file://`, поэтому отдаём `content://`
     * через FileProvider и временно даём временное разрешение на установку.
     */
    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Можно ли приложению ставить пакеты. Начиная с Android 8 — только с явным
     * разрешением пользователя, которое включается в настройках.
     */
    fun canRequestInstall(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return ctx.packageManager.canRequestPackageInstalls()
    }

    /** Экран настроек, где включается «Установка из неизвестных источников». */
    fun unknownSourcesSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun recordDismissed(ctx: Context, versionCode: Int) {
        // apply() — отдельный вызов: putInt возвращает Java-интерфейс Editor,
        // а не Kotlin-функцию, и именованные аргументы для неё запрещены.
        prefs(ctx).edit().putInt(K_DISMISSED, versionCode).apply()
    }

    /** Отметка «проверили» — для строки в экране «О программе». */
    fun lastCheckedAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_CHECK, 0L)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Отдельный клиент: у MietApi таймауты под короткий JSON-ответ (15/20 с),
     * а тут файл 17 МБ по мобильному интернету — нужен запас.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ───────────────────────── разбор ответа ─────────────────────────

    /**
     * `tag_name` может быть «0.29», «v0.29», «release-0.29» — достаём число.
     * Берём последнюю группу цифр: в альфе номер релиза стоит после точки
     * («0.29» → 29), а склейка всех цифр дала бы 029 и ломала сравнение
     * с `versionCode`.
     * Возвращаем null, если числа нет: сравнивать не с чем, и лучше не показать
     * диалог, чем показать мусор.
     */
    internal fun parseVersionCode(tag: String?): Int? {
        val lastMatch = tag?.trim()?.let { TAG_RE.findAll(it).lastOrNull()?.value }
        return lastMatch?.toIntOrNull()
    }

    // Тег вида «0.29», «v0.29», «29», «release-29», «v29» → число
    private val TAG_RE = Regex("[0-9]+")

    internal fun fetchLatest(url: String): UpdateInfo? {
        val req = okhttp3.Request.Builder()
            .url(url)
            // GitHub отдаёт 403 без User-Agent на части запросов.
            .header("User-Agent", "MIET-Schedule-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JsonParser.parseString(resp.body!!.string()).asJsonObject
            val tag = root.get("tag_name")?.asString
            val assets = root.getAsJsonArray("assets") ?: return null
            // Берём первый asset — это .apk. Релиз всегда с одним APK.
            val a = assets[0].asJsonObject
            val digest = a.get("digest")?.asString?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return UpdateInfo(
                versionCode = parseVersionCode(tag) ?: return null,
                tagName = tag ?: "",
                title = root.get("name")?.asString ?: tag.orEmpty(),
                notes = root.get("body")?.asString.orEmpty(),
                apkName = a.get("name")?.asString ?: "update.apk",
                downloadUrl = a.get("browser_download_url")?.asString ?: return null,
                sizeBytes = a.get("size")?.asLong ?: 0L,
                sha256 = digest,
            )
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Что известно о последнем релизе. */
data class UpdateInfo(
    val versionCode: Int,
    val tagName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
) {
    /**
     * Подпись версии для пользователя: берём сам тег релиза («0.29»), чтобы
     * в диалоге обновления и на экране «О программе» показывалось ровно то,
     * что опубликовано на GitHub.
     */
    val versionLabel: String = tagName.ifBlank { "сборка $versionCode" }

    /**
     * Человеческий размер: 16.7 МБ.
     *
     * Форматируем НЕ через `String.format`: он берёт десятичный разделитель из
     * локали, и на русском телефоне выдаёт «16,7 МБ» — с запятой. Тест это ловит.
     */
    val sizeLabel: String
        get() = if (sizeBytes <= 0) "" else {
            val mb = sizeBytes / 1048576.0
            // round-half-up, вручную: String.format берёт разделитель из локали.
            val tenths = (mb * 10 + 0.5).toInt()
            "${tenths / 10}.${tenths % 10} МБ"
        }
}

/**
 * Итог проверки по кнопке «Проверить обновления» — в отличие от фоновой проверки,
 * тут три честных исхода, а не один `null`:
 *  - [UpdateCheckResult.Available] — релиз новее установленной сборки;
 *  - [UpdateCheckResult.UpToDate] — релиз есть, но не новее нашей сборки;
 *  - [UpdateCheckResult.Failed] — до GitHub не дошли (нет сети, 404, лимит).
 *
 * Раньше всё это сводилось к `null`, и по кнопке нельзя было понять: то ли
 * обновлений правда нет, то ли GitHub не ответил, то ли пользователь сам
 * откладывал. «Обновлений нет» и «не удалось проверить» — разные вещи, и
 * врать пользователю тут нельзя.
 */
sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    /**
     * [latestVersion] — номер последнего релиза (для сравнения с нашей сборкой).
     * [tagName] — сам тег релиза: в альфе он читается как «0.30», и подставлять
     * «v» + номер значило бы показывать пользователю несуществующее имя.
     */
    data class UpToDate(val latestVersion: Int, val tagName: String) : UpdateCheckResult

    data class Failed(val reason: String) : UpdateCheckResult
}
