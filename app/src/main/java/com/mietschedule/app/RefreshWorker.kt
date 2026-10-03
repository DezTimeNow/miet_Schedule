package com.mietschedule.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Фоновая актуализация расписания раз в 6 часов.
 *
 * Зачем: до этого обновление шло только при открытии приложения или по
 * кнопке «Обновить всё». Кэш группы жил бессрочно, а условие загрузки
 * `force || lessons.isEmpty()` при непустом кэше сеть не дёргал. Человек,
 * открывавший расписание каждый день, видел данные, изменившиеся на сайте,
 * только спустя недели.
 *
 * Обновляются ВСЕ избранные группы, а не только открытая. Напоминания
 * строятся по избранным, поэтому их кэши тоже должны быть свежими: иначе
 * будильник звонил бы по расписанию, которое на сайте давно поменяли.
 *
 * Требования Android, важные для выбора периода:
 *  - `PeriodicWorkRequest` с периодом меньше 15 минут не выполнится;
 *  - «ровно в 6 часов» система не гарантирует: задача запускается в удобное
 *    окно, обычно с накоплением до нескольких часов. Для свежести расписания
 *    это приемлемо.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = GroupPrefs(ctx)
        val api = MietApi(ctx)

        if (prefs.role() != Role.STUDENT) {
            // Расписание преподавателя собирается из кэша всех 343 групп:
            // такой обход в фоне дорог и часто не нужен. Преподаватель сам
            // жмёт «Обновить всё» — кнопка ходит в сеть принудительно.
            Log.i(TAG, "Роль не студент, фоновое обновление пропускаю")
            return Result.success()
        }

        // Избранные группы — приоритет: по ним строятся напоминания.
        val favs = prefs.favGroups(Role.STUDENT).filter { it.isNotBlank() }.distinct()
        val current = prefs.load()?.trim()
        val targets = (if (current.isNullOrBlank()) favs else favs + current).distinct()

        if (targets.isEmpty()) {
            Log.i(TAG, "Нет ни избранных, ни выбранной группы — сеть не трогаю")
            return Result.success()
        }

        val fresh = mutableListOf<String>()
        val stale = targets.filter { group ->
            api.scheduleFresh(group).also { ok -> if (ok) fresh += group }
        }

        if (stale.isEmpty()) {
            Log.i(TAG, "Все кэши свежие (${fresh.size}), сеть не трогаю")
            return Result.success()
        }

        var failures = 0
        var updated = 0
        for (group in stale) {
            api.markCheck(Role.STUDENT, group)
            try {
                val raw = api.fetchSchedule(group)
                if (raw.isBlank()) {
                    Log.w(TAG, "Пустой ответ по $group, кэш не трогаю")
                    failures++
                } else {
                    api.cacheSchedule(group, raw)
                    updated++
                }
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Не обновил $group", e)
            }
        }

        if (updated > 0) {
            Log.i(TAG, "Обновлено групп: $updated из ${stale.size}")
            // Напоминания строим из кэша, поэтому пересчёт после обновления
            // обязателен: иначе будильники остались бы на старых временах.
            runCatching { ReminderScheduler.reschedule(ctx, api) }
                .onFailure { Log.w(TAG, "Напоминания не перепланированы", it) }
        }

        // Если хотя бы часть не удалась — повторим позже. Полный отказ
        // от всех групп означает проблему с сетью, а не с расписанием.
        return if (updated > 0 || failures == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "RefreshWorker"
        private const val UNIQUE = "schedule_refresh"

        /**
         * Зарегистрировать периодическую задачу. Повторный вызов безопасен.
         *
         * Вызывается НЕ из главного потока: первая инициализация WorkManager
         * читает базу и регистрирует компоненты, что давало «Skipped 485
         * frames» и ANR на старте приложения.
         */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(
                CachePolicy.REFRESH_PERIOD_MS, TimeUnit.MILLISECONDS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE,
                // KEEP: перезапуск процесса и повторный вызов не должны
                // сбрасывать таймер задачи на ноль.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}