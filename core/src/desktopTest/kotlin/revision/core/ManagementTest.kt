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
import revision.core.seed.Seeder
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

    private fun seeded() = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }

    // ---------- backup ----------

    @Test
    fun exportThenImportIntoAnEmptyDatabaseRebuildsEverything() {
        val a = seeded()
        val bio = TopicRepository(a, now).getBySubject("seed:biology")
        HistoryService(a, now).logManual("seed:biology", clock - DAY_MS, listOf(ManualTopic(bio[0].id, 30, 4), ManualTopic(bio[1].id, 20)), "note")
        SubjectRepository(a, now).setExamDate("seed:physics", clock + 30 * DAY_MS)
        val text = BackupService(a, now).export()

        val b = DatabaseFactory.inMemory()
        val result = BackupService(b, now).import(text)

        assertEquals(0, result.skipped)
        assertEquals(9L, b.subjectQueries.countAll().executeAsOne())
        assertEquals(a.topicQueries.countAll().executeAsOne(), b.topicQueries.countAll().executeAsOne())
        assertEquals(SessionRepository(a, now).totalsByTopic(clock), SessionRepository(b, now).totalsByTopic(clock))
        assertEquals(clock + 30 * DAY_MS, SubjectRepository(b, now).get("seed:physics")!!.exam_date)
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
        TopicEditor(a, now).rename(TopicRepository(a, now).getBySubject("seed:biology")[0].id, "Cells (my wording)")
        val result = BackupService(a, now).import(old)

        assertEquals("Cells (my wording)", TopicRepository(a, now).getBySubject("seed:biology")[0].title)
        assertEquals(0, result.updated)
    }

    @Test
    fun anArchiveMadeLaterIsNotUndoneByAnOlderBackup_andTravelsToTheOtherCopy() {
        val a = seeded()
        val b = seeded()
        clock += DAY_MS
        SubjectRepository(a, now).archive("seed:french")
        BackupService(b, now).import(BackupService(a, now).export())
        assertTrue(SubjectRepository(b, now).getAll().none { it.name == "French" })
    }

    @Test
    fun aRunningSessionIsNotExported() {
        val a = seeded()
        val bio = TopicRepository(a, now).getBySubject("seed:biology")
        SessionService(a, now).start("seed:biology", listOf(bio[0].id), clock)
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
        val text = BackupService(a, now).export().replace("\"seed:subject-that-does-not-exist\"", "x")
        val broken = text.replaceFirst("\"subjectId\": \"seed:biology\"", "\"subjectId\": \"nope\"")
        val result = BackupService(DatabaseFactory.inMemory(), now).import(broken)
        assertTrue(result.skipped > 0)
    }

    // ---------- editing ----------

    @Test
    fun movingATopicUpAndDownReordersSiblingsOnly() {
        val db = seeded()
        val ed = TopicEditor(db, now)
        val topics = TopicRepository(db, now)
        fun titles() = topics.getBySubject("seed:biology").filter { it.parent_id == null }.map { it.title }
        val first = titles()[0]; val second = titles()[1]
        val secondId = topics.getBySubject("seed:biology")[1].id
        ed.moveUp(secondId)
        assertEquals(listOf(second, first), titles().take(2))
        ed.moveDown(secondId)
        assertEquals(listOf(first, second), titles().take(2))
        ed.moveUp(topics.getBySubject("seed:biology")[0].id) // already first: no-op, no crash
        assertEquals(first, titles()[0])
    }

    @Test
    fun bulkAddCreatesOneTopicPerNonBlankLine() {
        val db = seeded()
        val ids = TopicEditor(db, now).addMany("seed:maths", null, listOf("Loci", "  ", "  Vectors 2  "))
        assertEquals(2, ids.size)
        val titles = TopicRepository(db, now).getBySubject("seed:maths").map { it.title }
        assertTrue("Vectors 2" in titles)
    }

    @Test
    fun bulkArchiveTakesChildrenAndRestoreBringsBackAncestors() {
        val db = seeded()
        val topics = TopicRepository(db, now)
        val ed = TopicEditor(db, now)
        val chapter = topics.getBySubject("seed:history").first { it.parent_id == null }
        val child = topics.getBySubject("seed:history").first { it.parent_id == chapter.id }
        ed.archiveMany(listOf(chapter.id))
        assertTrue(topics.getBySubject("seed:history").none { it.id == chapter.id || it.id == child.id })
        assertTrue(ed.archived().any { it.id == child.id })

        ed.restore(child.id) // restoring a child must also restore its archived parent
        val after = topics.getBySubject("seed:history").map { it.id }
        assertTrue(chapter.id in after && child.id in after)
    }

    @Test
    fun subjectEditingAndReordering() {
        val db = seeded()
        val ed = SubjectEditor(db, now)
        ed.edit("seed:maths", "Mathematics", "#123456", "Edexcel", clock + 10 * DAY_MS)
        val m = SubjectRepository(db, now).get("seed:maths")!!
        assertEquals("Mathematics", m.name); assertEquals("Edexcel", m.exam_board); assertEquals(clock + 10 * DAY_MS, m.exam_date)
        ed.moveUp("seed:maths")
        val names = SubjectRepository(db, now).getAll().map { it.id }
        assertEquals(listOf("seed:maths", "seed:history"), names.subList(6, 8))
    }

    // ---------- reset / restore ----------

    @Test
    fun restoreMissingBringsBackArchivedStartingTopicsButKeepsRenames() {
        val db = seeded()
        val topics = TopicRepository(db, now)
        val cells = topics.getBySubject("seed:biology")[0]
        TopicEditor(db, now).rename(cells.id, "My cells")
        TopicEditor(db, now).archiveMany(listOf(topics.getBySubject("seed:biology")[1].id))
        SubjectRepository(db, now).archive("seed:french")

        val changed = Seeder.restoreMissing(db, now)

        assertTrue(changed >= 2)
        assertEquals("My cells", topics.get(cells.id)!!.title)
        assertTrue(SubjectRepository(db, now).getAll().any { it.name == "French" })
        assertEquals(20, topics.getBySubject("seed:biology").size)
    }

    @Test
    fun resetAllWipesHistoryAndReseeds() {
        val db = seeded()
        val bio = TopicRepository(db, now).getBySubject("seed:biology")
        HistoryService(db, now).logManual("seed:biology", clock, listOf(ManualTopic(bio[0].id, 10, 3)), null)
        TopicEditor(db, now).addMany("seed:maths", null, listOf("Custom"))
        Seeder.resetAll(db, now)
        assertTrue(HistoryService(db, now).load().isEmpty())
        assertEquals(9L, db.subjectQueries.countAll().executeAsOne())
        assertFalse(TopicRepository(db, now).getBySubject("seed:maths").any { it.title == "Custom" })
    }

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
        val bio = TopicRepository(db, now).getBySubject("seed:biology")
        HistoryService(db, now).logManual("seed:biology", clock, listOf(ManualTopic(bio[0].id, 30, 5), ManualTopic(bio[1].id, 15)), null)
        val s = StatsService(db, now).subjectStats(clock + DAY_MS).first { it.subjectId == "seed:biology" }
        assertEquals(45 * minute, s.totalMs)
        assertEquals(20, s.leafCount)
        assertEquals(18, s.neverRevised)
        val top = StatsService(db, now).topicStats("seed:biology", clock + DAY_MS).first()
        assertEquals(bio[0].id, top.topicId)
    }

    @Test
    fun activityCountsDaysAndRuns() {
        val db = seeded()
        val bio = TopicRepository(db, now).getBySubject("seed:biology")
        val h = HistoryService(db, now)
        listOf(0L, 1L, 2L, 5L).forEach { back ->
            h.logManual("seed:biology", clock - back * DAY_MS, listOf(ManualTopic(bio[0].id, 10)), null)
        }
        val a = StatsService(db, now).activity(clock + 60 * minute, TimeZone.UTC)
        assertEquals(4, a.daysActive)
        assertEquals(3, a.currentRun)
        assertEquals(3, a.longestRun)
        assertNotNull(a)
    }
}
