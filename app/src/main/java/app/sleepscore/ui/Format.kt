package app.sleepscore.ui

import app.sleepscore.engine.Stats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val shortDayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

fun fmtTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

fun fmtDay(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFmt)

fun fmtDay(d: LocalDate): String = d.format(dayFmt)
fun fmtShortDay(d: LocalDate): String = d.format(shortDayFmt)

fun fmtDur(min: Int): String = Stats.fmtDur(min)

fun fmtHours(min: Int): String = "%.1fh".format(min / 60f)

fun fmtClock(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
