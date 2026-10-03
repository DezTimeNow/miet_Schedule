package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Проверки расчёта времени напоминания.
 *
 * Логика планировщика вынесена в чистые функции именно ради этих тестов:
 * раньше напоминаний не было, а ошибиться в них легко — минус 10 минут
 * при переходе через полночь даёт вчерашнее время и будильник «в прошлом».
 */
class ReminderTimeTest {

    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
        }

    @Test
    fun `за 10 минут до начала`() {
        val start = cal(2026, 10, 5, 9, 0).timeInMillis
        val notify = start - TimeUnit.MINUTES.toMillis(ReminderScheduler.LEAD_DEFAULT)
        assertEquals(cal(2026, 10, 5, 8, 50).timeInMillis, notify)
    }

    @Test
    fun `напоминание не позже начала пары`() {
        // Ключевое свойство: будильник не должен сработать позже начала
        // пары, иначе «напомнить» придёт, когда идти уже поздно.
        val start = cal(2026, 10, 5, 9, 0).timeInMillis
        val notify = start - TimeUnit.MINUTES.toMillis(ReminderScheduler.LEAD_DEFAULT)
        assertTrue("напоминание должно быть раньше начала", notify < start)
    }

    @Test
    fun `у первой пары напоминание не уезжает на предыдущие сутки`() {
        // 09:00 − 10 минут = 08:50 того же дня. Ошибка со знаком минуса у
        // суток здесь ломает расписание: будильник ставится «в прошлом».
        val start = cal(2026, 10, 5, 9, 0).timeInMillis
        val notify = start - TimeUnit.MINUTES.toMillis(ReminderScheduler.LEAD_DEFAULT)
        val c = Calendar.getInstance().apply { timeInMillis = notify }
        assertEquals(5, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(50, c.get(Calendar.MINUTE))
    }

    @Test
    fun `ведущий ноль времени из сервера разбирается верно`() {
        // Сервер отдаёт "0001-01-01T09:00:00": дата-пустышка, время настоящее.
        val raw = "0001-01-01T09:00:00"
        val part = raw.substringAfter('T').take(5)
        assertEquals("09:00", part)
        val hh = part.substringBefore(':').toInt()
        val mm = part.substringAfter(':').toInt()
        assertEquals(9, hh)
        assertEquals(0, mm)
    }

    @Test
    fun `пустое время не превращается в ноль`() {
        val raw = ""
        val part = raw.substringAfter('T').take(5)
        assertTrue("пустое время должно отбрасываться", part.length < 5)
    }

    @Test
    fun `время окончания не нужно для напоминания`() {
        // Берётся только TimeFrom: TimeTo в напоминании не участвует.
        val times = listOf(PairTime(code = 1, timeFrom = "0001-01-01T09:00:00", timeTo = "0001-01-01T10:20:00"))
        assertEquals("09:00", times[0].timeFrom?.substringAfter('T')?.take(5))
    }

    @Test
    fun `уведомление за десять минут по умолчанию`() {
        assertEquals(10L, ReminderScheduler.LEAD_DEFAULT)
    }

    @Test
    fun `доступные интервалы напоминания`() {
        // Требование: пользователь выбирает 1, 5, 10 или 15 минут.
        assertEquals(listOf(1L, 5L, 10L, 15L), ReminderScheduler.LEAD_OPTIONS)
    }

    @Test
    fun `все интервалы меньше получаса`() {
        // Дольше получаса — это уже не напоминание о паре, а сообщение
        // «скоро начнётся», и его лучше делать другим сообщением.
        assertTrue(ReminderScheduler.LEAD_OPTIONS.all { it <= 15L })
    }

    @Test
    fun `подпись интервала читаема`() {
        assertEquals("за 1 минуту", ReminderScheduler.leadLabel(1L))
        assertEquals("за 5 минут", ReminderScheduler.leadLabel(5L))
        assertEquals("за 10 минут", ReminderScheduler.leadLabel(10L))
        assertEquals("за 15 минут", ReminderScheduler.leadLabel(15L))
    }

    @Test
    fun `время напоминания считается от выбранного интервала`() {
        val start = cal(2026, 10, 5, 9, 0).timeInMillis
        for (minutes in ReminderScheduler.LEAD_OPTIONS) {
            val fire = start - TimeUnit.MINUTES.toMillis(minutes)
            val c = Calendar.getInstance().apply { timeInMillis = fire }
            assertEquals(
                "для интервала $minutes минут",
                9 * 60 - minutes.toInt(),
                c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            )
        }
    }
}

/**
 * Проверки TTL кэша расписания.
 *
 * Кэш группы раньше жил бессрочно, а условие загрузки было
 * `force || lessons.isEmpty()`: при непустом кэше сеть не дёргалась, и
 * приложение месяцами показывало расписание, изменившееся на сайте.
 */
class ScheduleCacheTtlTest {

    @Test
    fun `ttl равен шести часам`() {
        assertEquals(6L * 60 * 60 * 1000, CachePolicy.SCHEDULE_TTL_MS)
    }

    @Test
    fun `шесть часов — меньше суток`() {
        // Расписание может измениться на следующий день; суточный TTL был бы
        // слишком грубым, поминутный — слишком частым для 343 групп.
        assertTrue(CachePolicy.SCHEDULE_TTL_MS < 24L * 60 * 60 * 1000)
    }

    @Test
    fun `ttl совпадает с периодом фоновой задачи`() {
        // Фоновая задача просыпается каждые 6 часов, значит кэш успевает
        // протухнуть ровно к следующему её запуску, а не висеть вторые сутки.
        assertEquals(CachePolicy.SCHEDULE_TTL_MS, CachePolicy.REFRESH_PERIOD_MS)
    }
}

/**
 * Проверки подписи свежести данных.
 *
 * Раньше в шапке стояло «Обновлено: <время записи кэша>», из-за чего
 * недельный кэш читался как «обновлено сегодня».
 */
class LastUpdatedLabelTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `без данных подпись честная`() {
        assertEquals("данные ещё не загружены", LastUpdated.dataLabel(0L))
    }

    @Test
    fun `свежие данные без слова устарели`() {
        val now = 1_000_000_000_000L
        val label = LastUpdated.dataLabel(now - hour, now)
        assertFalse(label.contains("устарели"))
        assertTrue(label.startsWith("данные:"))
    }

    @Test
    fun `старые данные помечаются устаревшими`() {
        val now = 1_000_000_000_000L
        val label = LastUpdated.dataLabel(now - 30 * hour, now)
        assertTrue("подпись должна предупреждать об устаревании", label.contains("устарели"))
    }

    @Test
    fun `граница суток`() {
        val now = 1_000_000_000_000L
        // 23 часа — ещё свежо, 25 часов — уже устарело.
        assertFalse(LastUpdated.dataLabel(now - 23 * hour, now).contains("устарели"))
        assertTrue(LastUpdated.dataLabel(now - 25 * hour, now).contains("устарели"))
    }
}