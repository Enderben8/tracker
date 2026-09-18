package revision.core

import revision.core.data.SessionRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.seed.Seeder
import revision.core.seed.allSeedSubjects
import revision.core.seed.SeedTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataLayerTest {
    private var clock = 1_000_000L
    private val now: Now = { clock }

    private fun seededDb() = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }

    private fun countTopics(topics: List<SeedTopic>): Int = topics.sumOf { 1 + countTopics(it.children) }

    @Test
    fun seedsExactlyTheNineConfirmedSubjects() {
        val db = seededDb()
        val names = SubjectRepository(db, now).getAll().map { it.name }
        assertEquals(
            listOf("Biology", "Chemistry", "Physics", "Computer Science", "English Literature",
                "Geography", "History", "Maths", "French"),
            names,
        )
    }

    @Test
    fun seedingTwiceDoesNotDuplicate() {
        val db = seededDb()
        assertFalse(Seeder.seedIfEmpty(db, now))
        val expected = allSeedSubjects.sumOf { countTopics(it.topics) }.toLong()
        assertEquals(expected, db.topicQueries.countAll().executeAsOne())
    }

    @Test
    fun seededIdsAreUniqueAndParentsExist() {
        // INSERT OR REPLACE would silently swallow duplicate ids, so compare against the seed count.
        val db = seededDb()
        val topics = TopicRepository(db, now)
        val expected = allSeedSubjects.sumOf { countTopics(it.topics) }
        val all = allSeedSubjects.flatMap { topics.getBySubject(Seeder.subjectId(it.key)) }
        assertEquals(expected, all.size)
        val ids = all.map { it.id }.toSet()
        assertTrue(all.all { it.parent_id == null || it.parent_id in ids })
    }

    @Test
    fun historyHasOnlyTheFourChosenChapters() {
        val db = seededDb()
        val top = TopicRepository(db, now).getBySubject("seed:history").filter { it.parent_id == null }
        assertEquals(4, top.size)
    }

    @Test
    fun geographyExcludesUnchosenChapters() {
        val db = seededDb()
        val titles = TopicRepository(db, now).getBySubject("seed:geography").map { it.title }
        assertFalse("Hot deserts" in titles)
        assertFalse("Glacial landscapes" in titles)
        assertTrue("Cold environments" in titles)
        assertTrue("Coastal landscapes" in titles)
        assertTrue("River landscapes" in titles)
    }

    @Test
    fun computerSciencePagesAreNull() {
        val db = seededDb()
        assertTrue(TopicRepository(db, now).getBySubject("seed:computer-science").all { it.page_start == null })
    }

    @Test
    fun archivedTopicDisappearsAndTakesItsChildren() {
        val db = seededDb()
        val topics = TopicRepository(db, now)
        val macbeth = topics.getBySubject("seed:english-literature").first { it.title == "Macbeth" }
        topics.archive(macbeth.id)
        val remaining = topics.getBySubject("seed:english-literature")
        assertTrue(remaining.none { it.id == macbeth.id || it.parent_id == macbeth.id })
        assertNotNull(topics.get(macbeth.id)) // still in the table, just flagged deleted
    }

    @Test
    fun archivedSubjectDisappearsFromTheList() {
        val db = seededDb()
        val subjects = SubjectRepository(db, now)
        subjects.archive("seed:french")
        assertTrue(subjects.getAll().none { it.name == "French" })
    }

    @Test
    fun totalsAreDerivedFromSegmentsAndOpenSegmentsCountToNow() {
        val db = seededDb()
        val sessions = SessionRepository(db, now)
        val topic = TopicRepository(db, now).getBySubject("seed:biology").first()
        val sessionId = sessions.createSession("seed:biology", startedAt = 0L)
        val st = sessions.addTopicToSession(sessionId, topic.id)
        sessions.addClosedSegment(st, startedAt = 0L, endedAt = 60_000L)
        sessions.openSegment(st, startedAt = 100_000L)

        assertEquals(60_000L + 5_000L, sessions.totalsByTopic(nowMillis = 105_000L)[topic.id])
        assertEquals(1, sessions.openSegments().size)
        assertEquals(1, sessions.unfinishedSessions().size)
    }

    @Test
    fun deletedSessionNoLongerCountsTowardTotals() {
        val db = seededDb()
        val sessions = SessionRepository(db, now)
        val topic = TopicRepository(db, now).getBySubject("seed:biology").first()
        val sessionId = sessions.createSession("seed:biology", startedAt = 0L)
        val st = sessions.addTopicToSession(sessionId, topic.id)
        sessions.addClosedSegment(st, 0L, 60_000L)
        sessions.finishSession(sessionId, 60_000L, null)

        sessions.deleteSession(sessionId)

        assertNull(sessions.totalsByTopic(1_000_000L)[topic.id])
        assertTrue(sessions.getSession(sessionId)?.deleted == 1L)
    }
}
