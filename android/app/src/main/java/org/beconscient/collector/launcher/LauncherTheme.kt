package org.beconscient.collector.launcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val Ink = Color(0xFFF2F2F2)
private val Dim = Color(0xFF8C8C8C)
private val Line = Color(0xFF3A3A3A)
private val Raised = Color(0xFF141414)

/** Black and white only: nothing on the home screen should compete for attention. */
@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ink,
        onPrimary = Color.Black,
        secondary = Dim,
        onSecondary = Color.Black,
        background = Color.Black,
        onBackground = Ink,
        surface = Color.Black,
        onSurface = Ink,
        surfaceVariant = Raised,
        onSurfaceVariant = Dim,
        surfaceContainerHigh = Raised,
        surfaceContainerHighest = Raised,
        secondaryContainer = Ink,
        onSecondaryContainer = Color.Black,
        outline = Line,
        outlineVariant = Line,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

fun LocalTime.hhmm(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

fun Block.range(): String = "${start.hhmm()}-${end.hhmm()}"

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun daysLabel(days: Set<DayOfWeek>): String = when (days) {
    DayOfWeek.entries.toSet() -> "Every day"
    WEEKDAYS -> "Weekdays"
    setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> days.sorted().joinToString(", ") { it.short() }
}
