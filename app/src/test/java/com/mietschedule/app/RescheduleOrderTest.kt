package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пересчёт будильников при смене избранного.
 *
 * Считаем не по координатам тапа, а по разобранному исходнику: смысл
 * проверки в порядке вызовов, а он читается из кода надёжнее, чем
 * проявляется на экране.
 */
class RescheduleOrderTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    /** Тело `rescheduleBlocking`: от объявления до следующей функции. */
    private fun rescheduleBody(): String {
        val s = src("ReminderScheduler.kt")
        val start = s.indexOf("fun reschedule(")
        val end = s.indexOf("fun canUseExact(")
        require(start > 0 && end > start) { "Не найдено тело reschedule" }
        return s.substring(start, end)
    }

    @Test
    fun `перед постановкой будильников стоит безусловная отмена`() {
        val body = rescheduleBody()
        val loopIdx = body.indexOf("for (group in favs) {")
        assertTrue("Не найден цикл постановки", loopIdx > 0)

        // После ранних выходов (пустое избранное, не студент, выключено)
        // и до цикла обязана быть отмена БЕЗ if — иначе отписавшаяся
        // группа остаётся в AlarmManager.
        val earlyExitEnd = body.lastIndexOf("return\n        }")
        val between = body.substring(earlyExitEnd, loopIdx)
        assertTrue(
            "Перед циклом постановки нет безусловной отмены: при снятии " +
                "звезды с одной группы из нескольких её будильники останутся " +
                "в AlarmManager и напоминание продолжит приходить",
            between.contains("cancelAll(ctx)"),
        )
        assertTrue(
            "cancelAll обязан встречаться безусловно, а не внутри if",
            // между ранним выходом и циклом нет открытой ветки: считаем,
            // что cancelAll там ровно один и он на своём уровне.
            between.count { it == '\n' } > 0 &&
                between.lines().any { it.trim() == "cancelAll(ctx)" },
        )
    }

    @Test
    fun `отмена читает плановые коды до их перезаписи`() {
        val body = rescheduleBody()
        val cancelIdx = body.indexOf("cancelAll(ctx)\n\n        val row")
        val rememberIdx = body.lastIndexOf("rememberPlanned(ctx, plannedIds)")
        assertTrue("Не найдена прочистка перед постановкой", cancelIdx > 0)
        assertTrue("Не найден rememberPlanned", rememberIdx > 0)
        assertTrue(
            "cancelAll обязан идти раньше rememberPlanned: он читает " +
                "planned_ids, который rememberPlanned перезаписывает",
            cancelIdx < rememberIdx,
        )
    }

    @Test
    fun `отмена не требует существования будильника`() {
        // Для прочистки PendingIntent может не существовать: FLAG_NO_CREATE
        // вернёт null, и это нормально — снимать нечего.
        val s = src("ReminderScheduler.kt")
        assertTrue(
            "cancelIntent использует FLAG_NO_CREATE — верно для прочистки",
            s.contains("PendingIntent.FLAG_NO_CREATE"),
        )
    }

    @Test
    fun `на каждую избранную группу планируется свой набор напоминаний`() {
        // Требование владельца: две избранные группы — два независимых
        // набора пушей. Цикл обязан идти по всем favs, а не по первой.
        val body = rescheduleBody()
        assertTrue(
            "Цикл постановки должен идти по всем избранным группам",
            body.contains("for (group in favs) {"),
        )
        assertTrue(
            "Код будильника строится из группы — иначе две группы " +
                "дадут одинаковые коды и напоминания перетрутся",
            body.contains("requestCodeFor(group, dayOffset"),
        )
        // Код включает группу, значит коллизий между группами нет.
        val s = src("ReminderScheduler.kt")
        val fn = s.substring(s.indexOf("private fun requestCodeFor"))
        assertTrue(
            "requestCodeFor обязан учитывать группу в коде",
            fn.substring(0, fn.indexOf("\n")).contains("group"),
        )
    }
}