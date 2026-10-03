package com.mietschedule.app

import android.os.Build

/**
 * Что приложение знает о себе в момент, когда человек жмёт
 * «Сообщить об ошибке».
 *
 * Смысл в том, чтобы пользователю НЕ пришлось описывать проблему. Он
 * жмёт кнопку — письмо уже содержит версию, роль, выбранное расписание и
 * состояние данных. По этому набору сразу видно, что у человека: пустое
 * расписание, ошибка загрузки, старая версия или другая роль.
 *
 * Личные данные сюда НЕ попадают: ни адреса почты, ни идентификаторов
 * устройства, ни выбранной группы. Про группу тоже — см. [anonymize].
 */
data class ReportData(
    val versionName: String,
    val versionCode: Int,
    val role: Role,
    /** Что открыто у человека: группа, ФИО или аудитория. */
    val selection: String?,
    /** Сколько пар на экране. */
    val lessonsShown: Int,
    /** Текст ошибки с экрана расписания, если он показан. */
    val errorText: String?,
    /** Метка последней проверки обновлений — нужна, чтобы понять, не застрял ли пользователь. */
    val updateCheckedAt: Long,
) {

    /**
     * Android-версия и модель: главное, когда решают, баг это или
     * несовместимость. Точное устройство и его идентификаторы сюда не
     * берём — для разбора расписания они не нужны.
     */
    val deviceName: String get() = "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER}"

    /**
     * Обезличивание выбора.
     *
     * Выбранная группа или аудитория — это персональные данные
     * пользователя, и в письме ему они не нужны: воспроизводимость
     * обеспечивают версия, роль и состояние данных. ФИО преподавателя
     * оставляем: расписание преподавателя целиком собирается из чужих
     * групп, и понять, чьё именно сломалось, без этого нельзя.
     */
    private fun anonymize(sel: String?): String {
        val value = sel?.takeIf { it.isNotBlank() } ?: return "не выбрано"
        return when (role) {
            Role.TEACHER -> value
            else -> "выбрано (${value.length} символов)"
        }
    }

    /** Обратный адрес. Ключ Web3Forms работает без него, но с ним письмо можно ответить. */
    fun replyTo(): String = ""

    /** Тело письма: сначала сводка, потом текст пользователя. */
    fun body(comment: String): String = buildString {
        appendLine("Состояние приложения на момент отправки")
        appendLine("─────────────────────────────────")
        appendLine("Версия: $versionName (сборка $versionCode)")
        appendLine("Роль: ${role.title}")
        appendLine("Выбрано: ${anonymize(selection)}")
        appendLine("Пар на экране: $lessonsShown")
        appendLine("Состояние данных: ${if (errorText.isNullOrBlank()) "ошибок нет" else "ошибка на экране"}")
        if (!errorText.isNullOrBlank()) appendLine("Текст ошибки: $errorText")
        appendLine("Проверка обновлений: ${lastCheckLabel(updateCheckedAt)}")
        appendLine("Устройство: $deviceName")
        appendLine()
        if (comment.isNotBlank()) {
            appendLine("Комментарий пользователя:")
            appendLine(comment.trim())
        } else {
            appendLine("Комментарий не указан.")
        }
    }
}