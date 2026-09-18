package revision.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 3_725_000 -> "1:02:05", 65_000 -> "1:05". */
fun formatClock(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "${m}:$ss"
}

/** 5_400_000 -> "1h 30m", 45_000 -> "<1m", 600_000 -> "10m". */
fun formatDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0L) / 60_000
    if (minutes == 0L) return if (millis > 0) "<1m" else "0m"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

fun localDate(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

/** "14:05" in the user's local time zone. */
fun formatTimeOfDay(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}

/** Epoch millis of local midnight at the start of [date]. */
fun startOfDayMillis(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    date.atStartOfDayIn(zone).toEpochMilliseconds()
