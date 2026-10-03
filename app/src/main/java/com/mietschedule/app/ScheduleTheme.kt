package com.mietschedule.app

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Режим темы: 0 — как в системе, 1 — светлая, 2 — тёмная.
 *
 * Раньше тема была жёстко задана светлой (`lightColorScheme` в `onCreate`),
 * и выбора не существовало: читающий расписание вечером получал белый
 * фон на полной яркости.
 */
const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

private const val PREFS_THEME = "theme_prefs"
private const val KEY_MODE = "theme_mode"

/**
 * Цвета, которых нет в [androidx.compose.material3.ColorScheme].
 *
 * Отдельный [staticCompositionLocalOf], потому что набор не меняется
 * при рекомпозиции внутри темы — значения всегда те же для текущей темы.
 */
class AppColors(
    val fav: Color,
    val onFav: Color,
    val currentGroup: Color,
    val rowGroup: Color,
    val favStar: Color,
    val starInactive: Color,
    val error: Color,
    val errorContainer: Color,
    val labBadge: Color,
    val onLabBadge: Color,
    val muted: Color,
    val dim: Color,
    val dayHeader: Color,
    val onDayHeader: Color,
    // Корпус и выводы микросхемы в мини-игре. Отдельные цвета, потому что
    // на белой и на чёрной теме один оттенок читается по-разному.
    val chipBody: Color,
    val chipBodyDeep: Color,
    val chipPin: Color,
) {
    companion object {
        val Light = AppColors(
            fav = ScheduleColorScheme.fav,
            onFav = ScheduleColorScheme.onFav,
            currentGroup = ScheduleColorScheme.currentGroup,
            rowGroup = ScheduleColorScheme.rowGroup,
            favStar = ScheduleColorScheme.favStar,
            starInactive = ScheduleColorScheme.starInactive,
            error = ScheduleColorScheme.error,
            errorContainer = ScheduleColorScheme.errorContainer,
            labBadge = ScheduleColorScheme.labBadge,
            onLabBadge = ScheduleColorScheme.onLabBadge,
            muted = ScheduleColorScheme.muted,
            dim = ScheduleColorScheme.dim,
            dayHeader = ScheduleColorScheme.dayHeader,
            onDayHeader = ScheduleColorScheme.onDayHeader,
            chipBody = ScheduleColorScheme.chipBody,
            chipBodyDeep = ScheduleColorScheme.chipBodyDeep,
            chipPin = ScheduleColorScheme.chipPin,
        )

        val Dark = AppColors(
            fav = ScheduleColorScheme.darkFav,
            onFav = ScheduleColorScheme.darkOnFav,
            currentGroup = ScheduleColorScheme.darkCurrentGroup,
            rowGroup = ScheduleColorScheme.darkRowGroup,
            favStar = ScheduleColorScheme.darkFavStar,
            starInactive = ScheduleColorScheme.darkStarInactive,
            error = ScheduleColorScheme.darkError,
            errorContainer = ScheduleColorScheme.darkErrorContainer,
            labBadge = ScheduleColorScheme.darkLabBadge,
            onLabBadge = ScheduleColorScheme.darkOnLabBadge,
            muted = ScheduleColorScheme.darkMuted,
            dim = ScheduleColorScheme.darkDim,
            dayHeader = ScheduleColorScheme.darkDayHeader,
            onDayHeader = ScheduleColorScheme.darkOnDayHeader,
            chipBody = ScheduleColorScheme.darkChipBody,
            chipBodyDeep = ScheduleColorScheme.darkChipBodyDeep,
            chipPin = ScheduleColorScheme.darkChipPin,
        )
    }
}

val LocalAppColors = staticCompositionLocalOf { AppColors.Light }

private val LightScheme = lightColorScheme(
    primary = ScheduleColorScheme.primary,
    onPrimary = ScheduleColorScheme.onPrimary,
    primaryContainer = ScheduleColorScheme.primaryContainer,
    onPrimaryContainer = ScheduleColorScheme.onPrimaryContainer,
    secondary = ScheduleColorScheme.secondary,
    onSecondary = ScheduleColorScheme.onSecondary,
    surface = ScheduleColorScheme.surface,
    onSurface = ScheduleColorScheme.onSurface,
    surfaceVariant = ScheduleColorScheme.surfaceVariant,
    onSurfaceVariant = ScheduleColorScheme.onSurfaceVariant,
    outline = ScheduleColorScheme.outline,
    background = ScheduleColorScheme.background,
    onBackground = ScheduleColorScheme.onSurface,
    error = ScheduleColorScheme.error,
)

private val DarkScheme = darkColorScheme(
    primary = ScheduleColorScheme.darkPrimary,
    onPrimary = ScheduleColorScheme.darkOnPrimary,
    primaryContainer = ScheduleColorScheme.darkPrimaryContainer,
    onPrimaryContainer = ScheduleColorScheme.darkOnPrimaryContainer,
    secondary = ScheduleColorScheme.darkSecondary,
    onSecondary = ScheduleColorScheme.darkOnSecondary,
    surface = ScheduleColorScheme.darkSurface,
    onSurface = ScheduleColorScheme.darkOnSurface,
    surfaceVariant = ScheduleColorScheme.darkSurfaceVariant,
    onSurfaceVariant = ScheduleColorScheme.darkOnSurfaceVariant,
    outline = ScheduleColorScheme.darkOutline,
    background = ScheduleColorScheme.darkBackground,
    onBackground = ScheduleColorScheme.darkOnSurface,
    error = ScheduleColorScheme.darkError,
)

/**
 * Тема приложения.
 *
 * [themeMode] — сохранённый выбор пользователя; при [THEME_SYSTEM] берётся
 * настройка системы. Экран переключения вызывает [saveThemeMode] и меняет
 * состояние, тема пересобирается целиком.
 */
@Composable
fun ScheduleTheme(
    themeMode: Int,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkScheme else LightScheme
    val appColors = if (dark) AppColors.Dark else AppColors.Light

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

// ── Хранение выбора ───────────────────────────────────────────────────

fun loadThemeMode(ctx: Context): Int =
    ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        .getInt(KEY_MODE, THEME_SYSTEM)

fun saveThemeMode(ctx: Context, mode: Int) {
    ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_MODE, mode)
        .apply()
}

/** Читаемое название режима для переключателя. */
fun themeModeLabel(mode: Int): String = when (mode) {
    THEME_LIGHT -> "Светлая"
    THEME_DARK -> "Тёмная"
    else -> "Как в системе"
}