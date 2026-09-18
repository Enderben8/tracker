package revision.core.stats

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/** A stretch of studying, as start/end epoch millis. */
data class TimeSpan(val startMs: Long, val endMs: Long)

/**
 * Milliseconds studied on each of the given local days. A span that crosses midnight is split
 * between the two days it touches. Days come from the user's time zone; storage stays in UTC.
 */
fun dailyTotals(spans: List<TimeSpan>, days: List<LocalDate>, zone: TimeZone): Map<LocalDate, Long> {
    val out = days.associateWith { 0L }.toMutableMap()
    for (day in days) {
        val from = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val to = day.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
        var total = 0L
        for (s in spans) {
            val overlap = minOf(s.endMs, to) - maxOf(s.startMs, from)
            if (overlap > 0) total += overlap
        }
        out[day] = total
    }
    return out
}

/** The last [count] local days ending on the day containing [nowMs], oldest first. */
fun lastDays(nowMs: Long, count: Int, zone: TimeZone): List<LocalDate> {
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return (count - 1 downTo 0).map { today.plus(DatePeriod(days = -it)) }
}
