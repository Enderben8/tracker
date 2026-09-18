package revision.core

import kotlinx.datetime.TimeZone
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.data.TopicStateRepository
import revision.core.scheduling.DAY_MS
import revision.core.scheduling.TodayService
import revision.core.seed.Seeder
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import revision.core.timer.SessionService
import revision.core.timer.TopicRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulingIntegrationTest {
    private var clock = 400 * DAY_MS
    private val now: Now = { clock }
    private val db = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }
    private val sessions = SessionService(db, now)
    private val history = HistoryService(db, now)
    private val today = TodayService(db, now)
    private val states = TopicStateRepository(db)
    private val bio = TopicRepository(db, now).getBySubject("seed:biology")
    private val minute = 60_000L

    private fun runSession(topicIds: List<String>, ratings: Map<Int, Int?>, minutes: Long = 10) {
        val id = sessions.start("seed:biology", topicIds, clock)
        clock += minutes * minute
        val active = sessions.active(clock)!!
        val rated = ratings.mapNotNull { (i, r) -> r?.let { active.topics[i].sessionTopicId to TopicRating(it) } }.toMap()
        sessions.stop(id, rated, at = clock)
    }

    @Test
    fun stoppingARatedSessionSchedulesTheTopic() {
        val topic = bio[0].id
        runSession(listOf(topic), mapOf(0 to 4))
        val s = assertNotNull(states.get(topic))
        assertEquals(1.0, s.interval_days)
        assertEquals(clock, s.last_revised_at)
        assertEquals(clock + DAY_MS, s.due_at)
    }

    @Test
    fun anUnratedTopicOnlyGetsItsLastRevisedDate() {
        val topic = bio[1].id
        runSession(listOf(topic), emptyMap())
        val s = assertNotNull(states.get(topic))
        assertEquals(clock, s.last_revised_at)
        assertNull(s.due_at)
        assertEquals(0L, s.repetitions)
    }

    @Test
    fun aTopicAddedButNeverTimedIsNotMarkedRevised() {
        val id = sessions.start("seed:biology", listOf(bio[0].id), clock)
        sessions.addTopic(id, bio[1].id, makeActive = false, at = clock)
        clock += 5 * minute
        sessions.stop(id, at = clock)
        assertNull(states.get(bio[1].id))
        assertNotNull(states.get(bio[0].id))
    }

    @Test
    fun manualLogsAlsoSchedule_butNeverOverwriteNewerState() {
        val topic = bio[2].id
        runSession(listOf(topic), mapOf(0 to 5))
        val newer = states.get(topic)!!
        // log something that happened three days ago, rated badly
        history.logManual("seed:biology", clock - 3 * DAY_MS, listOf(ManualTopic(topic, 20, 1)), null)
        assertEquals(newer.due_at, states.get(topic)!!.due_at)
    }

    @Test
    fun revisingATopicRemovesItFromTheTopOfTheQueue() {
        val first = today.suggestions().first().topicId
        val subject = TopicRepository(db, now).get(first)!!.subject_id
        val id = sessions.start(subject, listOf(first), clock)
        clock += 10 * minute
        sessions.stop(id, mapOf(sessions.active(clock)!!.topics[0].sessionTopicId to TopicRating(5)), at = clock)
        assertTrue(today.suggestions().none { it.topicId == first })
    }

    @Test
    fun suggestionsAreLeafTopicsOnly() {
        val parents = TopicRepository(db, now).getAll().mapNotNull { it.parent_id }.toSet()
        assertTrue(today.suggestions().none { it.topicId in parents })
    }

    @Test
    fun archivedSubjectsNeverAppear() {
        SubjectRepository(db, now).getAll().filter { it.id != "seed:maths" }.forEach { SubjectRepository(db, now).archive(it.id) }
        assertTrue(today.suggestions().all { it.subjectId == "seed:maths" })
    }

    @Test
    fun aSubjectWhoseExamHasPassedDisappears() {
        SubjectRepository(db, now).setExamDate("seed:physics", clock - 2 * DAY_MS)
        assertTrue(today.suggestions().none { it.subjectId == "seed:physics" })
    }

    @Test
    fun timeSummaryTotalsToday() {
        runSession(listOf(bio[0].id), emptyMap(), minutes = 25)
        val summary = today.timeSummary(clock, TimeZone.UTC)
        assertEquals(7, summary.days.size)
        assertEquals(25 * minute, summary.last7Ms)
    }
}
