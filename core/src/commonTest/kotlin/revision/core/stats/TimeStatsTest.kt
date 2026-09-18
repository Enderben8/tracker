package revision.core.stats

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeStatsTest {
    private val utc = TimeZone.UTC
    private val d1 = LocalDate(2026, 9, 17)
    private val d2 = LocalDate(2026, 9, 18)
    private val min = 60_000L

    @Test
    fun aSessionCrossingMidnightIsSplitBetweenTheTwoDays() {
        val midnight = d2.atStartOfDayIn(utc).toEpochMilliseconds()
        val span = TimeSpan(midnight - 30 * min, midnight + 45 * min)
        val totals = dailyTotals(listOf(span), listOf(d1, d2), utc)
        assertEquals(30 * min, totals.getValue(d1))
        assertEquals(45 * min, totals.getValue(d2))
    }

    @Test
    fun daysWithNothingAreZero() {
        assertEquals(0L, dailyTotals(emptyList(), listOf(d1), utc).getValue(d1))
    }

    @Test
    fun lastDaysEndsTodayOldestFirst() {
        val nowMs = d2.atStartOfDayIn(utc).toEpochMilliseconds() + 5 * 3_600_000L
        val days = lastDays(nowMs, 7, utc)
        assertEquals(7, days.size)
        assertEquals(d2, days.last())
        assertEquals(LocalDate(2026, 9, 12), days.first())
    }

    @Test
    fun theSameMomentIsADifferentDayInAnotherTimeZone() {
        val nowMs = d1.atStartOfDayIn(utc).toEpochMilliseconds() + 23 * 3_600_000L // 23:00 UTC
        val tokyo = TimeZone.of("Asia/Tokyo")
        assertEquals(d1, lastDays(nowMs, 1, utc).single())
        assertEquals(d2, lastDays(nowMs, 1, tokyo).single())
    }
}
