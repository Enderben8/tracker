package revision.core

import kotlinx.datetime.TimeZone
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.data.TopicStateRepository
import revision.core.scheduling.DAY_MS
import revision.core.scheduling.TodayService
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
    private val db = DatabaseFactory.inMemory().also { TestCatalogue.installAll(it, now) }
    private val sessions = SessionService(db, now)
    private val history = HistoryService(db, now)
    private val today = TodayService(db, now)
    private val states = TopicStateRepository(db)
    private val bio = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
    private val minute = 60_000L

    private fun runSession(topicIds: List<String>, ratings: Map<Int, Int?>, minutes: Long = 10) {
        val id = sessions.start(TestCatalogue.BIOLOGY, topicIds, clock)
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
        val id = sessions.start(TestCatalogue.BIOLOGY, listOf(bio[0].id), clock)
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
        history.logManual(TestCatalogue.BIOLOGY, clock - 3 * DAY_MS, listOf(ManualTopic(topic, 20, 1)), null)
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
        SubjectRepository(db, now).getAll().filter { it.id != TestCatalogue.MATHS }.forEach { SubjectRepository(db, now).archive(it.id) }
        assertTrue(today.suggestions().all { it.subjectId == TestCatalogue.MATHS })
    }

    @Test
    fun aSubjectWhoseExamHasPassedDisappears() {
        SubjectRepository(db, now).setExamDate(TestCatalogue.PHYSICS, clock - 2 * DAY_MS)
        assertTrue(today.suggestions().none { it.subjectId == TestCatalogue.PHYSICS })
    }

    @Test
    fun timeSummaryTotalsToday() {
        runSession(listOf(bio[0].id), emptyMap(), minutes = 25)
        val summary = today.timeSummary(clock, TimeZone.UTC)
        assertEquals(7, summary.days.size)
        assertEquals(25 * minute, summary.last7Ms)
    }

    @Test
    fun revisingWithinASubjectIsRankedOnlyFromThatSubject_andIsNotCappedAtThree() {
        val list = today.suggestionsForSubject(TestCatalogue.BIOLOGY, limit = 12)
        assertEquals(12, list.size)
        assertTrue(list.all { it.subjectId == TestCatalogue.BIOLOGY })
        assertEquals(list.sortedByDescending { it.score }.map { it.topicId }.toSet(), list.map { it.topicId }.toSet())
        // the overall queue still limits each subject to three
        assertTrue(today.suggestions().groupBy { it.subjectId }.values.all { it.size <= 3 })
    }

    @Test
    fun aTopicYouJustRevisedDropsOutOfTheSubjectsList() {
        val first = today.suggestionsForSubject(TestCatalogue.BIOLOGY, limit = 12).first().topicId
        val id = sessions.start(TestCatalogue.BIOLOGY, listOf(first), clock)
        clock += 10 * minute
        sessions.stop(id, mapOf(sessions.active(clock)!!.topics[0].sessionTopicId to TopicRating(5)), at = clock)
        assertTrue(today.suggestionsForSubject(TestCatalogue.BIOLOGY, limit = 12).none { it.topicId == first })
    }

    @Test
    fun anOverdueTopicComesFirstWithinItsSubject() {
        val overdue = bio[3].id
        val id = sessions.start(TestCatalogue.BIOLOGY, listOf(overdue), clock)
        clock += 10 * minute
        sessions.stop(id, mapOf(sessions.active(clock)!!.topics[0].sessionTopicId to TopicRating(2)), at = clock)   // due in 1 day
        clock += 5 * DAY_MS
        val top = today.suggestionsForSubject(TestCatalogue.BIOLOGY, limit = 5, nowMs = clock).first()
        assertEquals(overdue, top.topicId)
        assertTrue(top.reason.contains("overdue"), top.reason)
    }

    @Test
    fun aSubjectWhoseExamHasPassedCanStillBeBrowsed() {
        SubjectRepository(db, now).setExamDate(TestCatalogue.PHYSICS, clock - 3 * DAY_MS)
        assertTrue(today.suggestions().none { it.subjectId == TestCatalogue.PHYSICS })
        assertEquals(12, today.suggestionsForSubject(TestCatalogue.PHYSICS, limit = 12).size)
    }

    @Test
    fun suggestionsSayWhereATopicSitsInTheSubject() {
        // Specification subjects are a section with topics under it, so each suggestion names its section.
        val geo = today.suggestionsForSubject(TestCatalogue.GEOGRAPHY, limit = 40)
        assertTrue(geo.any { it.context.isNotEmpty() }, "nested topics should show their section")
        val sections = TopicRepository(db, now).getBySubject(TestCatalogue.GEOGRAPHY)
            .filter { it.parent_id == null }.map { it.title }
        assertTrue(geo.filter { it.context.isNotEmpty() }.all { it.context.substringAfterLast(" › ") in sections })
    }
}
