package revision.core.scheduling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {
    private val now = 400 * DAY_MS
    private val hour = 3_600_000L

    private fun cand(id: String, subject: String = "s1", exam: Long? = null, state: SrsState? = null) =
        Candidate(id, subject, "Subject $subject", "Topic $id", exam, state)

    private fun state(lastAgo: Long, dueIn: Long, interval: Double = 3.0, reps: Int = 2) =
        SrsState(now - lastAgo, now + dueIn, interval, 2.5, reps)

    @Test
    fun neverRevisedTopicOutranksOneRevisedYesterday() {
        val yesterday = cand("a", state = state(lastAgo = 25 * hour, dueIn = 2 * DAY_MS))
        val never = cand("b")
        val ranked = Scheduler.rank(listOf(yesterday, never), now)
        assertEquals("b", ranked.first().topicId)
    }

    @Test
    fun rating5PushesDueFurtherOutThanRating3() {
        fun after(rating: Int): SrsState {
            var s: SrsState? = null
            var t = now
            repeat(4) { s = Sm2.review(s, rating, t); t += 10 * DAY_MS }
            return s!!
        }
        val five = after(5)
        val three = after(3)
        assertTrue(five.intervalDays > three.intervalDays)
        assertTrue(five.dueAt!! - five.lastRevisedAt!! > three.dueAt!! - three.lastRevisedAt!!)
    }

    @Test
    fun rating2ResetsTheIntervalToOneDay() {
        var s: SrsState? = null
        repeat(4) { s = Sm2.review(s, 5, now) }
        assertTrue(s!!.intervalDays > 3.0)
        val failed = Sm2.review(s, 2, now)
        assertEquals(1.0, failed.intervalDays)
        assertEquals(0, failed.repetitions)
        assertEquals(now + DAY_MS, failed.dueAt)
    }

    @Test
    fun secondSuccessfulReviewIsThreeDaysNotSix() {
        val first = Sm2.review(null, 4, now)
        val second = Sm2.review(first, 4, now)
        assertEquals(1.0, first.intervalDays)
        assertEquals(3.0, second.intervalDays)
    }

    @Test
    fun easeFactorNeverFallsBelowItsFloorAcross50RatingOneReviews() {
        var s: SrsState? = null
        repeat(50) {
            s = Sm2.review(s, 1, now)
            assertTrue(s!!.easeFactor >= 1.3, "ease was ${s!!.easeFactor}")
        }
        assertEquals(1.3, s!!.easeFactor)
    }

    @Test
    fun intervalIsCapped() {
        var s: SrsState? = null
        repeat(40) { s = Sm2.review(s, 5, now) }
        assertEquals(120.0, s!!.intervalDays)
    }

    @Test
    fun skippingARatingLeavesTheScheduleUntouched() {
        val before = state(lastAgo = 5 * DAY_MS, dueIn = DAY_MS, interval = 4.0, reps = 3)
        val after = Sm2.touch(before, now)
        assertEquals(now, after.lastRevisedAt)
        assertEquals(before.dueAt, after.dueAt)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(before.easeFactor, after.easeFactor)
        assertEquals(before.repetitions, after.repetitions)
    }

    @Test
    fun aTopicRevisedTenMinutesAgoIsNotInTheTopTen() {
        val fresh = cand("just", subject = "s6", state = SrsState(now - 10 * 60_000L, now + DAY_MS, 1.0, 2.5, 1))
        val others = (1..5).flatMap { s -> (1..3).map { cand("t$s-$it", subject = "s$s") } }
        val ranked = Scheduler.rank(others + fresh, now)
        assertEquals(10, ranked.size)
        assertTrue(ranked.none { it.topicId == "just" })
    }

    @Test
    fun nearerExamRanksHigherWhenEverythingElseMatches() {
        val near = (1..3).map { cand("n$it", subject = "near", exam = now + 10 * DAY_MS) }
        val far = (1..3).map { cand("f$it", subject = "far", exam = now + 100 * DAY_MS) }
        val ranked = Scheduler.rank(far + near, now)
        assertEquals("near", ranked.first().subjectId)
        assertEquals(listOf("near", "near", "near"), ranked.take(3).map { it.subjectId })
    }

    @Test
    fun aSubjectWithNoExamDateIsNotCrowdedOutByOneWithADate() {
        val dated = (1..20).map { cand("d$it", subject = "dated", exam = now + 5 * DAY_MS) }
        val undated = (1..20).map { cand("u$it", subject = "undated") }
        val ranked = Scheduler.rank(dated + undated, now)
        assertTrue(ranked.any { it.subjectId == "undated" })
    }

    @Test
    fun anOverdueTopicInASubjectWhoseExamHasPassedDoesNotTopTheQueue() {
        val overdue = state(lastAgo = 20 * DAY_MS, dueIn = -10 * DAY_MS, interval = 5.0)
        val passed = cand("old", subject = "done", exam = now - 5 * DAY_MS, state = overdue)
        val current = cand("new", subject = "live")
        val ranked = Scheduler.rank(listOf(passed, current), now)
        assertEquals("new", ranked.first().topicId)
        assertTrue(ranked.none { it.subjectId == "done" })
    }

    @Test
    fun anExamHappeningTodayIsNotYetPassed() {
        val today = cand("x", exam = now - 2 * hour)  // exam day started 2 hours ago
        assertEquals(1, Scheduler.rank(listOf(today), now).size)
    }

    @Test
    fun queueIsStableAndIgnoresInputOrder() {
        val all = (1..30).map { cand("t$it", subject = "s${it % 4}") }
        val a = Scheduler.rank(all, now)
        val b = Scheduler.rank(all, now)
        val c = Scheduler.rank(all.reversed(), now)
        assertEquals(a, b)
        assertEquals(a.map { it.topicId }, c.map { it.topicId })
    }

    @Test
    fun noSubjectTakesMoreThanThreeSlotsAndQueueIsCapped() {
        val all = (1..40).map { cand("t$it", subject = "s${it % 6}") }
        val ranked = Scheduler.rank(all, now)
        assertTrue(ranked.size <= 10)
        assertTrue(ranked.groupBy { it.subjectId }.values.all { it.size <= 3 })
    }

    @Test
    fun reasonsNameTheStrongestSignal() {
        val never = Scheduler.rank(listOf(cand("a")), now).single()
        assertEquals("not revised yet", never.reason)

        val overdue = state(lastAgo = 19 * DAY_MS, dueIn = -9 * DAY_MS, interval = 10.0)
        assertEquals("9 days overdue", Scheduler.rank(listOf(cand("b", state = overdue)), now).single().reason)

        val recent = state(lastAgo = 3 * DAY_MS, dueIn = 2 * DAY_MS)
        val exam = Scheduler.rank(listOf(cand("c", exam = now + 12 * DAY_MS, state = recent)), now).single()
        assertEquals("Subject s1 exam in 12 days", exam.reason)
    }

    @Test
    fun scoreFollowsTheDocumentedFormula() {
        // never revised, no exam date: 60 (never) + 0.3 * 50 (neutral exam pressure) = 75
        assertEquals(75.0, Scheduler.score(cand("a"), now, SchedulerConfig()).score, 1e-9)
    }
}
