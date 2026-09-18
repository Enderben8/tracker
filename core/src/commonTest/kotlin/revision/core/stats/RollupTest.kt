package revision.core.stats

import kotlinx.datetime.LocalDate
import revision.core.db.Topic
import kotlin.test.Test
import kotlin.test.assertEquals

class RollupTest {
    private fun t(id: String, parent: String?) = Topic(id, "s", parent, null, id, null, 0, null, 0, 0)

    @Test
    fun parentsSumTheirChildrenAndTakeTheNewestRevisionDate() {
        val topics = listOf(t("a", null), t("a1", "a"), t("a2", "a"), t("b", null))
        val r = rollUp(topics, totalMs = mapOf("a1" to 10L, "a2" to 5L, "b" to 7L), lastRevised = mapOf("a1" to 100L, "a2" to 300L))
        assertEquals(15L, r.getValue("a").totalMs)
        assertEquals(300L, r.getValue("a").lastRevisedAt)
        assertEquals(2, r.getValue("a").leafCount)
        assertEquals(0, r.getValue("a").neverRevisedLeaves)
        assertEquals(1, r.getValue("b").neverRevisedLeaves)
    }

    @Test
    fun aParentIsNotCountedAsALeaf() {
        val topics = listOf(t("g", null), t("l1", "g"), t("l2", "g"))
        assertEquals(2, rollUp(topics, emptyMap(), emptyMap()).getValue("g").leafCount)
    }

    private val today = LocalDate(2026, 9, 18)
    private fun d(back: Int) = LocalDate.fromEpochDays(today.toEpochDays() - back)

    @Test
    fun runCountsBackFromTodayOrYesterday() {
        assertEquals(3, activityFrom(setOf(d(0), d(1), d(2)), today).currentRun)
        assertEquals(2, activityFrom(setOf(d(1), d(2)), today).currentRun)   // today not done yet: not a break
        assertEquals(0, activityFrom(setOf(d(2), d(3)), today).currentRun)   // yesterday missed
    }

    @Test
    fun longestRunAndDaysActive() {
        val a = activityFrom(setOf(d(0), d(1), d(4), d(5), d(6), d(7)), today)
        assertEquals(6, a.daysActive)
        assertEquals(4, a.longestRun)
    }

    @Test
    fun nothingRevisedIsAllZeros() {
        assertEquals(Activity(0, 0, 0), activityFrom(emptySet(), today))
    }
}
