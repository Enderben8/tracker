package revision.core

import revision.core.data.TopicRepository
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import revision.core.timer.SessionService
import revision.core.timer.TopicRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionServiceTest {
    private var clock = 1_000_000_000L
    private val now: Now = { clock }
    private val db = DatabaseFactory.inMemory().also { TestCatalogue.installAll(it, now) }
    private val service = SessionService(db, now)
    private val history = HistoryService(db, now)
    private val bio = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
    private val cells = bio[0].id
    private val enzymes = bio[4].id
    private val minute = 60_000L

    @Test
    fun pauseDoesNotCountAndSwitchingSplitsTimeBetweenTopics() {
        service.start(TestCatalogue.BIOLOGY, listOf(cells, enzymes), at = clock)
        clock += 10 * minute
        service.pause(clock)
        clock += 30 * minute // paused: must not count
        service.resume(clock)
        clock += 5 * minute
        val a = service.active(clock)!!
        service.switchTo(a.topics[1].sessionTopicId, clock)
        clock += 7 * minute

        val s = service.active(clock)!!
        assertEquals(15 * minute, s.topics[0].totalMs)
        assertEquals(7 * minute, s.topics[1].totalMs)
        assertEquals(22 * minute, s.totalMs)
        assertFalse(s.isPaused)
    }

    @Test
    fun topicsCanBeAddedMidSession() {
        val id = service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        clock += minute
        service.addTopic(id, enzymes, makeActive = true, at = clock)
        clock += 2 * minute
        val s = service.active(clock)!!
        assertEquals(2, s.topics.size)
        assertEquals(minute, s.topics[0].totalMs)
        assertEquals(2 * minute, s.topics[1].totalMs)
    }

    @Test
    fun stopSavesRatingsAndMovesSessionToHistory() {
        val id = service.start(TestCatalogue.BIOLOGY, listOf(cells, enzymes), at = clock)
        clock += 20 * minute
        val s = service.active(clock)!!
        service.stop(id, mapOf(s.topics[0].sessionTopicId to TopicRating(4)), "good", clock)

        assertNull(service.active(clock))
        val entry = history.load().single()
        assertEquals(20 * minute, entry.totalMs)
        assertEquals(4, entry.topics[0].rating)
        assertNull(entry.topics[1].rating) // ratings are skippable
        assertEquals("good", entry.notes)
    }

    @Test
    fun skippingTheRatingsStillSavesTheSession() {
        val id = service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        clock += minute
        service.stop(id, at = clock)
        assertEquals(1, history.load().size)
    }

    @Test
    fun crashWithOpenSegmentIsDetectedAndDatedByTheHeartbeatNotByNow() {
        service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        val start = clock
        clock += 10 * minute
        service.heartbeat(clock)
        clock += 9 * 60 * minute // PC asleep / app dead for hours

        val d = assertNotNull(service.findDangling(clock))
        assertEquals(start + 10 * minute, d.lastAliveAt)
        assertTrue(d.implausiblyLong)

        service.recoverAndSave(d)
        assertEquals(10 * minute, history.load().single().totalMs)
        assertNull(service.findDangling(clock))
    }

    @Test
    fun recoveringAsPausedKeepsTheSessionResumable() {
        service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        clock += 5 * minute
        service.heartbeat(clock)
        clock += 2 * minute
        val d = assertNotNull(service.findDangling(clock))
        assertFalse(d.implausiblyLong)
        service.recoverAsPaused(d)

        val s = assertNotNull(service.active(clock))
        assertTrue(s.isPaused)
        assertEquals(5 * minute, s.totalMs)
    }

    @Test
    fun aPausedSessionIsNotReportedAsDangling() {
        service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        clock += minute
        service.pause(clock)
        assertNull(service.findDangling(clock + 1000 * minute))
        assertNotNull(service.active(clock))
    }

    @Test
    fun discardRemovesTheSessionEntirely() {
        val id = service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        clock += minute
        service.discard(id)
        assertNull(service.active(clock))
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun cannotStartTwoSessionsAtOnce() {
        service.start(TestCatalogue.BIOLOGY, listOf(cells), at = clock)
        var failed = false
        try { service.start(TestCatalogue.BIOLOGY, listOf(enzymes), at = clock) } catch (e: IllegalStateException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun manualLoggingSynthesisesOneSegmentPerTopic() {
        val start = clock
        history.logManual(TestCatalogue.BIOLOGY, start, listOf(ManualTopic(cells, 30, 5), ManualTopic(enzymes, 15)), "at school")
        val e = history.load().single()
        assertTrue(e.isManual)
        assertEquals(45 * minute, e.totalMs)
        assertEquals(30 * minute, e.topics[0].ms)
        assertEquals(5, e.topics[0].rating)
        assertEquals(start + 45 * minute, e.endedAt)
    }

    @Test
    fun fixingADurationChangesTheTotals() {
        val id = history.logManual(TestCatalogue.BIOLOGY, clock, listOf(ManualTopic(cells, 30)), null)
        val st = history.load().single().topics[0].sessionTopicId
        history.setTopicMinutes(id, st, 45)
        assertEquals(45 * minute, history.load().single().totalMs)
    }

    @Test
    fun sessionCrossingMidnightStaysOneSession() {
        val start = 23L * 60 * minute // arbitrary epoch offset; only the arithmetic matters
        val id = service.start(TestCatalogue.BIOLOGY, listOf(cells), at = start)
        service.stop(id, at = start + 90 * minute)
        assertEquals(90 * minute, history.load().single().totalMs)
    }
}
