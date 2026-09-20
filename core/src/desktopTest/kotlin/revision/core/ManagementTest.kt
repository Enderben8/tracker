package revision.core

import kotlinx.datetime.TimeZone
import revision.core.backup.BackupService
import revision.core.data.SessionRepository
import revision.core.data.SettingsRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.manage.SubjectEditor
import revision.core.manage.TopicEditor
import revision.core.scheduling.DAY_MS
import revision.core.scheduling.SchedulerConfig
import revision.core.scheduling.SchedulerConfigStore
import revision.core.stats.StatsService
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import revision.core.timer.SessionService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManagementTest {
    private var clock = 400 * DAY_MS
    private val now: Now = { clock }
    private val minute = 60_000L

    private fun seeded() = DatabaseFactory.inMemory().also { TestCatalogue.installAll(it, now) }

    // ---------- backup ----------

    @Test
    fun exportThenImportIntoAnEmptyDatabaseRebuildsEverything() {
        val a = seeded()
        val bio = TopicRepository(a, now).getBySubject(TestCatalogue.BIOLOGY)
        HistoryService(a, now).logManual(TestCatalogue.BIOLOGY, clock - DAY_MS, listOf(ManualTopic(bio[0].id, 30, 4), ManualTopic(bio[1].id, 20)), "note")
        SubjectRepository(a, now).setExamDate(TestCatalogue.PHYSICS, clock + 30 * DAY_MS)
        val text = BackupService(a, now).export()

        val b = DatabaseFactory.inMemory()
        val result = BackupService(b, now).import(text)

        assertEquals(0, result.skipped)
        assertEquals(a.subjectQueries.countAll().executeAsOne(), b.subjectQueries.countAll().executeAsOne())
        assertEquals(a.topicQueries.countAll().executeAsOne(), b.topicQueries.countAll().executeAsOne())
        assertEquals(SessionRepository(a, now).totalsByTopic(clock), SessionRepository(b, now).totalsByTopic(clock))
        assertEquals(clock + 30 * DAY_MS, SubjectRepository(b, now).get(TestCatalogue.PHYSICS)!!.exam_date)
        assertEquals(1, HistoryService(b, now).load().size)
        assertEquals(4, HistoryService(b, now).load().single().topics[0].rating)
    }

    @Test
    fun importingTheSameFileTwiceChangesNothing() {
        val a = seeded()
        val text = BackupService(a, now).export()
        val second = BackupService(a, now).import(text)
        assertEquals(0, second.added)
        assertEquals(0, second.updated)
    }

    @Test
    fun theNewerEditWinsAndOlderOnesAreIgnored() {
        val a = seeded()
        val old = BackupService(a, now).export()

        clock += DAY_MS
        TopicEditor(a, now).rename(TopicRepository(a, now).getBySubject(TestCatalogue.BIOLOGY)[0].id, "Cells (my wording)")
        val result = BackupService(a, now).import(old)

        assertEquals("Cells (my wording)", TopicRepository(a, now).getBySubject(TestCatalogue.BIOLOGY)[0].title)
        assertEquals(0, result.updated)
    }

    @Test
    fun anArchiveMadeLaterIsNotUndoneByAnOlderBackup_andTravelsToTheOtherCopy() {
        val a = seeded()
        val b = seeded()
        clock += DAY_MS
        SubjectRepository(a, now).archive(TestCatalogue.FRENCH)
        BackupService(b, now).import(BackupService(a, now).export())
        assertTrue(SubjectRepository(b, now).getAll().none { it.name == "French" })
    }

    @Test
    fun aRunningSessionIsNotExported() {
        val a = seeded()
        val bio = TopicRepository(a, now).getBySubject(TestCatalogue.BIOLOGY)
        SessionService(a, now).start(TestCatalogue.BIOLOGY, listOf(bio[0].id), clock)
        val b = DatabaseFactory.inMemory()
        BackupService(b, now).import(BackupService(a, now).export())
        assertTrue(SessionRepository(b, now).unfinishedSessions().isEmpty())
        assertEquals(0L, b.sessionQueries.exportSessions().executeAsList().size.toLong())
    }

    @Test
    fun aFileThatIsNotABackupIsRejectedWithAReadableMessage() {
        val e = assertFailsWith<IllegalArgumentException> { BackupService(seeded(), now).import("hello") }
        assertTrue(e.message!!.contains("Revision Tracker backup"))
        assertFailsWith<IllegalArgumentException> { BackupService(seeded(), now).import("""{"format":"other"}""") }
    }

    @Test
    fun rowsPointingAtMissingParentsAreSkippedNotCrashed() {
        val a = seeded()
        val text = BackupService(a, now).export().replace("\"spec:subject-that-does-not-exist\"", "x")
        val broken = text.replaceFirst("\"subjectId\": \"${TestCatalogue.BIOLOGY}\"", "\"subjectId\": \"nope\"")
        val result = BackupService(DatabaseFactory.inMemory(), now).import(broken)
        assertTrue(result.skipped > 0)
    }

    // ---------- editing ----------

    @Test
    fun movingATopicUpAndDownReordersSiblingsOnly() {
        val db = seeded()
        val ed = TopicEditor(db, now)
        val topics = TopicRepository(db, now)
        fun roots() = topics.getBySubject(TestCatalogue.BIOLOGY).filter { it.parent_id == null }
        fun titles() = roots().map { it.title }
        val first = titles()[0]; val second = titles()[1]
        val secondId = roots()[1].id
        ed.moveUp(secondId)
        assertEquals(listOf(second, first), titles().take(2))
        ed.moveDown(secondId)
        assertEquals(listOf(first, second), titles().take(2))
        ed.moveUp(roots()[0].id) // already first: no-op, no crash
        assertEquals(first, titles()[0])
    }

    @Test
    fun bulkAddCreatesOneTopicPerNonBlankLine() {
        val db = seeded()
        val ids = TopicEditor(db, now).addMany(TestCatalogue.MATHS, null, listOf("Loci", "  ", "  Vectors 2  "))
        assertEquals(2, ids.size)
        val titles = TopicRepository(db, now).getBySubject(TestCatalogue.MATHS).map { it.title }
        assertTrue("Vectors 2" in titles)
    }

    @Test
    fun bulkArchiveTakesChildrenAndRestoreBringsBackAncestors() {
        val db = seeded()
        val topics = TopicRepository(db, now)
        val ed = TopicEditor(db, now)
        val chapter = topics.getBySubject(TestCatalogue.HISTORY).first { it.parent_id == null }
        val child = topics.getBySubject(TestCatalogue.HISTORY).first { it.parent_id == chapter.id }
        ed.archiveMany(listOf(chapter.id))
        assertTrue(topics.getBySubject(TestCatalogue.HISTORY).none { it.id == chapter.id || it.id == child.id })
        assertTrue(ed.archived().any { it.id == child.id })

        ed.restore(child.id) // restoring a child must also restore its archived parent
        val after = topics.getBySubject(TestCatalogue.HISTORY).map { it.id }
        assertTrue(chapter.id in after && child.id in after)
    }

    @Test
    fun subjectEditingAndReordering() {
        val db = seeded()
        val ed = SubjectEditor(db, now)
        ed.edit(TestCatalogue.MATHS, "Mathematics", "#123456", "Edexcel", clock + 10 * DAY_MS)
        val m = SubjectRepository(db, now).get(TestCatalogue.MATHS)!!
        assertEquals("Mathematics", m.name); assertEquals("Edexcel", m.exam_board); assertEquals(clock + 10 * DAY_MS, m.exam_date)
        val before = SubjectRepository(db, now).getAll().map { it.id }
        val at = before.indexOf(TestCatalogue.MATHS)
        ed.moveUp(TestCatalogue.MATHS)
        val after = SubjectRepository(db, now).getAll().map { it.id }
        assertEquals(listOf(TestCatalogue.MATHS, before[at - 1]), after.subList(at - 1, at + 1))
    }

    // Restoring and starting over are the catalogue's job now, and are covered by CatalogueTest.

    // ---------- settings & stats ----------

    @Test
    fun schedulerWeightsSurviveAReload() {
        val db = seeded()
        val store = SchedulerConfigStore(SettingsRepository(db, now))
        assertEquals(SchedulerConfig(), store.load())
        store.save(SchedulerConfig(overdueWeight = 10.0, queueSize = 5))
        val loaded = store.load()
        assertEquals(10.0, loaded.overdueWeight); assertEquals(5, loaded.queueSize)
        store.resetToDefaults()
        assertEquals(SchedulerConfig(), store.load())
    }

    @Test
    fun coverageCountsNeverRevisedLeavesPerSubject() {
        val db = seeded()
        val bio = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
        val leaves = bio.filter { topic -> bio.none { it.parent_id == topic.id } }
        HistoryService(db, now).logManual(
            TestCatalogue.BIOLOGY, clock, listOf(ManualTopic(leaves[0].id, 30, 5), ManualTopic(leaves[1].id, 15)), null,
        )
        val s = StatsService(db, now).subjectStats(clock + DAY_MS).first { it.subjectId == TestCatalogue.BIOLOGY }
        assertEquals(45 * minute, s.totalMs)
        assertEquals(TestCatalogue.leafCount(TestCatalogue.biology), s.leafCount)
        assertEquals(s.leafCount - 2, s.neverRevised)
        val top = StatsService(db, now).topicStats(TestCatalogue.BIOLOGY, clock + DAY_MS).first()
        assertEquals(leaves[0].id, top.topicId)
    }

    @Test
    fun activityCountsDaysAndRuns() {
        val db = seeded()
        val bio = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY)
        val h = HistoryService(db, now)
        listOf(0L, 1L, 2L, 5L).forEach { back ->
            h.logManual(TestCatalogue.BIOLOGY, clock - back * DAY_MS, listOf(ManualTopic(bio[0].id, 10)), null)
        }
        val a = StatsService(db, now).activity(clock + 60 * minute, TimeZone.UTC)
        assertEquals(4, a.daysActive)
        assertEquals(3, a.currentRun)
        assertEquals(3, a.longestRun)
        assertNotNull(a)
    }
}
