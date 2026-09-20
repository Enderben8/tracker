package revision.core

import revision.core.catalogue.Catalogue
import revision.core.catalogue.CatalogueInstaller
import revision.core.data.SessionRepository
import revision.core.data.SettingsRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataLayerTest {
    private var clock = 1_000_000L
    private val now: Now = { clock }

    private fun installedDb() = TestCatalogue.database(now)

    @Test
    fun nothingExistsUntilSubjectsAreChosen() {
        val db = DatabaseFactory.inMemory()
        assertEquals(0L, db.subjectQueries.countAll().executeAsOne())
        assertTrue(!CatalogueInstaller.isSetUp(SettingsRepository(db, now)))
    }

    @Test
    fun installingWritesTheChosenSubjectsAndMarksSetupDone() {
        val db = DatabaseFactory.inMemory()
        TestCatalogue.install(db, now, listOf(TestCatalogue.biology, TestCatalogue.physics))

        val names = SubjectRepository(db, now).getAll().map { it.name }
        assertEquals(listOf("Biology", "Physics"), names)
        assertTrue(CatalogueInstaller.isSetUp(SettingsRepository(db, now)))
    }

    @Test
    fun installingTwiceDoesNotDuplicate() {
        val db = installedDb()
        val before = db.topicQueries.countAll().executeAsOne()
        TestCatalogue.installAll(db, now)
        assertEquals(before, db.topicQueries.countAll().executeAsOne())
    }

    @Test
    fun installedIdsAreUniqueAndParentsExist() {
        // INSERT OR REPLACE would silently swallow duplicate ids, so compare against the expected count.
        val db = installedDb()
        val topics = TopicRepository(db, now)
        val expected = Catalogue.all.sumOf { TestCatalogue.topicCount(it) }
        val all = Catalogue.all.flatMap { topics.getBySubject(CatalogueInstaller.subjectIdFor(it)) }
        assertEquals(expected, all.size)
        val ids = all.map { it.id }.toSet()
        assertTrue(all.all { it.parent_id == null || it.parent_id in ids })
    }

    @Test
    fun topicsCarryTheSpecificationsOwnCodes() {
        val db = installedDb()
        val cellBiology = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
            .first { it.title == "Cell biology" }
        assertEquals("4.1", cellBiology.code)
        val children = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
            .filter { it.parent_id == cellBiology.id }
        assertEquals(listOf("Cell structure", "Cell division", "Transport in cells"), children.map { it.title })
    }

    @Test
    fun theBoardIsRecordedAgainstTheSubject() {
        val db = installedDb()
        assertEquals("AQA", SubjectRepository(db, now).get(TestCatalogue.BIOLOGY)?.exam_board)
    }

    @Test
    fun archivedTopicDisappearsAndTakesItsChildren() {
        val db = installedDb()
        val topics = TopicRepository(db, now)
        val group = topics.getBySubject(TestCatalogue.BIOLOGY).first { it.parent_id == null }
        topics.archive(group.id)
        val remaining = topics.getBySubject(TestCatalogue.BIOLOGY)
        assertTrue(remaining.none { it.id == group.id || it.parent_id == group.id })
        assertNotNull(topics.get(group.id)) // still in the table, just flagged deleted
    }

    @Test
    fun archivedSubjectDisappearsFromTheList() {
        val db = installedDb()
        val subjects = SubjectRepository(db, now)
        subjects.archive(TestCatalogue.FRENCH)
        assertTrue(subjects.getAll().none { it.name == "French" })
    }

    @Test
    fun totalsAreDerivedFromSegmentsAndOpenSegmentsCountToNow() {
        val db = installedDb()
        val sessions = SessionRepository(db, now)
        val topic = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).first()
        val sessionId = sessions.createSession(TestCatalogue.BIOLOGY, startedAt = 0L)
        val st = sessions.addTopicToSession(sessionId, topic.id)
        sessions.addClosedSegment(st, startedAt = 0L, endedAt = 60_000L)
        sessions.openSegment(st, startedAt = 100_000L)

        assertEquals(60_000L + 5_000L, sessions.totalsByTopic(nowMillis = 105_000L)[topic.id])
        assertEquals(1, sessions.openSegments().size)
        assertEquals(1, sessions.unfinishedSessions().size)
    }

    @Test
    fun deletedSessionNoLongerCountsTowardTotals() {
        val db = installedDb()
        val sessions = SessionRepository(db, now)
        val topic = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).first()
        val sessionId = sessions.createSession(TestCatalogue.BIOLOGY, startedAt = 0L)
        val st = sessions.addTopicToSession(sessionId, topic.id)
        sessions.addClosedSegment(st, 0L, 60_000L)
        sessions.finishSession(sessionId, 60_000L, null)

        sessions.deleteSession(sessionId)

        assertNull(sessions.totalsByTopic(1_000_000L)[topic.id])
        assertTrue(sessions.getSession(sessionId)?.deleted == 1L)
    }
}
