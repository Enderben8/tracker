package revision.core

import revision.core.catalogue.Catalogue
import revision.core.catalogue.CatalogueInstaller
import revision.core.catalogue.ResetService
import revision.core.catalogue.SetupSettings
import revision.core.catalogue.SubjectChoice
import revision.core.catalogue.Tier
import revision.core.data.SettingsRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The catalogue itself, and installing a chosen selection from it. */
class CatalogueTest {
    private var clock = 1_000_000L
    private val now: Now = { clock }

    // ---- the data ------------------------------------------------------------------------

    @Test
    fun everySubjectIsUsableAndDistinct() {
        assertTrue(Catalogue.all.isNotEmpty())
        assertEquals(Catalogue.all.size, Catalogue.all.map { it.ref }.toSet().size, "refs must be unique")
        Catalogue.all.forEach { subject ->
            assertTrue(subject.groups.isNotEmpty(), "${subject.ref} has no groups")
            assertTrue(subject.topicCount > 0, "${subject.ref} has no topics")
            assertTrue(subject.sourceUrl.startsWith("https://"), "${subject.ref} has no source")
            assertTrue(subject.checkedOn.isNotBlank(), "${subject.ref} has no checked date")
        }
    }

    @Test
    fun aSubjectOfferedBySeveralBoardsAgreesOnNameAndColour() {
        Catalogue.bySubject.forEach { subject ->
            assertEquals(1, subject.specs.map { it.name }.toSet().size, "${subject.key} names differ by board")
            assertEquals(1, subject.specs.map { it.colour }.toSet().size, "${subject.key} colours differ by board")
        }
    }

    @Test
    fun subjectColoursAreDistinguishable() {
        val colours = Catalogue.bySubject.map { it.colour }
        assertEquals(colours.size, colours.toSet().size, "two subjects share a colour")
    }

    @Test
    fun everySetOfOptionsCanBeSatisfied() {
        Catalogue.all.forEach { subject ->
            subject.groups.filter { it.choice != null }.groupBy { it.choice!!.group }.forEach { (label, groups) ->
                assertTrue(groups.size >= groups.first().choice!!.pick, "${subject.ref}: cannot pick from $label")
            }
            // The default choice must never produce an empty subject.
            val choice = CatalogueInstaller.defaultChoice(subject)
            assertTrue(CatalogueInstaller.groupsFor(choice).isNotEmpty(), "${subject.ref} installs empty")
        }
    }

    // ---- installing ----------------------------------------------------------------------

    @Test
    fun idsComeFromTheChoiceSoTwoDevicesAgree() {
        val one = TestCatalogue.database(now)
        val two = TestCatalogue.database { clock + 5_000 }
        val idsOne = TopicRepository(one, now).getBySubject(TestCatalogue.BIOLOGY).map { it.id }
        val idsTwo = TopicRepository(two, now).getBySubject(TestCatalogue.BIOLOGY).map { it.id }
        assertEquals(idsOne, idsTwo)
        assertTrue(idsOne.first().startsWith("spec:aqa/biology-8461:"))
    }

    @Test
    fun mixingBoardsPerSubjectInstallsEachWithItsOwnBoard() {
        val db = DatabaseFactory.inMemory()
        // AQA History alongside OCR Computer Science: the ordinary case, not an edge case.
        TestCatalogue.install(db, now, listOf(TestCatalogue.history, TestCatalogue.ocrComputerScience))

        val subjects = SubjectRepository(db, now).getAll()
        assertEquals(listOf("History", "Computer Science"), subjects.map { it.name })
        assertEquals(listOf("AQA", "OCR"), subjects.map { it.exam_board })
        assertEquals(subjects.size, subjects.map { it.id }.toSet().size)
    }

    @Test
    fun theSameSubjectOnTwoBoardsStaysSeparate() {
        // They are different courses, so they must not collide on ids or merge over sync.
        val db = DatabaseFactory.inMemory()
        TestCatalogue.install(db, now, listOf(TestCatalogue.biology, TestCatalogue.edexcelBiology))

        val subjects = SubjectRepository(db, now).getAll()
        assertEquals(2, subjects.size)
        assertEquals(listOf("AQA", "Edexcel"), subjects.map { it.exam_board })
        val aqaTopics = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).map { it.id }
        val edexcelTopics = TopicRepository(db, now)
            .getBySubject(CatalogueInstaller.subjectIdFor(TestCatalogue.edexcelBiology)).map { it.id }
        assertTrue(aqaTopics.isNotEmpty() && edexcelTopics.isNotEmpty())
        assertTrue(aqaTopics.none { it in edexcelTopics })
    }

    @Test
    fun optionsOnlyInstallWhatWasChosen() {
        val db = DatabaseFactory.inMemory()
        val options = TestCatalogue.history.groups.filter { it.choice != null }
        val kept = options.first()
        CatalogueInstaller.install(db, now, listOf(SubjectChoice(TestCatalogue.history, groups = setOf(kept.ref))))

        val titles = TopicRepository(db, now).getBySubject(TestCatalogue.HISTORY).map { it.title }
        assertTrue(kept.title in titles)
        options.drop(1).forEach { assertFalse(it.title in titles, "${it.title} should not be installed") }
    }

    @Test
    fun foundationTierLeavesOutHigherOnlyContent() {
        val higherOnly = TestCatalogue.maths.groups
            .flatMap { it.topics }.first { it.tier == Tier.HIGHER }

        val foundation = DatabaseFactory.inMemory()
        CatalogueInstaller.install(foundation, now, listOf(SubjectChoice(TestCatalogue.maths, tier = Tier.FOUNDATION)))
        val higher = DatabaseFactory.inMemory()
        CatalogueInstaller.install(higher, now, listOf(SubjectChoice(TestCatalogue.maths, tier = Tier.HIGHER)))

        assertFalse(higherOnly.title in TopicRepository(foundation, now).getBySubject(TestCatalogue.MATHS).map { it.title })
        assertTrue(higherOnly.title in TopicRepository(higher, now).getBySubject(TestCatalogue.MATHS).map { it.title })
    }

    @Test
    fun restoreBringsBackArchivedTopicsButKeepsRenames() {
        val db = TestCatalogue.database(now)
        val topics = TopicRepository(db, now)
        val biology = topics.getBySubject(TestCatalogue.BIOLOGY)
        val renamed = biology.first { it.parent_id != null }
        val archived = biology.first { it.parent_id != null && it.id != renamed.id }

        topics.update(renamed.copy(title = "My cells"))
        topics.archive(archived.id)
        SubjectRepository(db, now).archive(TestCatalogue.FRENCH)

        clock += 1000
        val changed = CatalogueInstaller.restoreMissing(db, now)

        assertTrue(changed >= 2)
        assertEquals("My cells", topics.get(renamed.id)?.title)
        assertTrue(topics.getBySubject(TestCatalogue.BIOLOGY).any { it.id == archived.id })
        assertTrue(SubjectRepository(db, now).getAll().any { it.id == TestCatalogue.FRENCH })
    }

    // ---- starting over -------------------------------------------------------------------

    @Test
    fun startingOverArchivesEverythingSoTheDeletionCanTravel() {
        val db = TestCatalogue.database(now)
        clock += 1000

        ResetService.startOver(db, now)

        assertTrue(SubjectRepository(db, now).getAll().isEmpty())
        assertTrue(TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).isEmpty())
        // Soft, not hard: the rows remain as tombstones for the other device to pick up.
        assertTrue(db.subjectQueries.countAll().executeAsOne() > 0)
        assertTrue(db.subjectQueries.exportAll().executeAsList().all { it.deleted == 1L })
        assertFalse(CatalogueInstaller.isSetUp(SettingsRepository(db, now)))
    }

    @Test
    fun startingOverKeepsHistoryByDefault() {
        val db = TestCatalogue.database(now)
        val topic = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).first { it.parent_id != null }
        HistoryService(db, now).logManual(TestCatalogue.BIOLOGY, clock, listOf(ManualTopic(topic.id, 30, 4)), null)
        clock += 1000

        ResetService.startOver(db, now, keepHistory = true)

        assertEquals(1, HistoryService(db, now).load().size)
    }

    @Test
    fun startingOverCanEraseHistoryToo() {
        val db = TestCatalogue.database(now)
        val topic = TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).first { it.parent_id != null }
        HistoryService(db, now).logManual(TestCatalogue.BIOLOGY, clock, listOf(ManualTopic(topic.id, 30, 4)), null)
        clock += 1000

        ResetService.startOver(db, now, keepHistory = false)

        assertTrue(HistoryService(db, now).load().isEmpty())
    }

    @Test
    fun startingOverCanKeepChosenSubjects() {
        val db = TestCatalogue.database(now)
        clock += 1000

        ResetService.startOver(db, now, keepSubjectIds = setOf(TestCatalogue.BIOLOGY))

        val left = SubjectRepository(db, now).getAll()
        assertEquals(listOf("Biology"), left.map { it.name })
        assertTrue(TopicRepository(db, now).getBySubject(TestCatalogue.BIOLOGY).isNotEmpty())
        // Setup stays finished, because a subject survived.
        assertTrue(CatalogueInstaller.isSetUp(SettingsRepository(db, now)))
    }

    @Test
    fun theChosenSelectionIsRememberedForLater() {
        val db = DatabaseFactory.inMemory()
        TestCatalogue.install(db, now, listOf(TestCatalogue.biology))
        val settings = SettingsRepository(db, now)

        assertTrue(settings.get(SetupSettings.SELECTION)!!.contains("aqa/biology-8461"))
        assertEquals(listOf("aqa/biology-8461"), CatalogueInstaller.savedChoices(settings).map { it.subject.ref })
    }
}
