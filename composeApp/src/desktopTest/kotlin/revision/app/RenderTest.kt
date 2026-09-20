package revision.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import revision.core.DatabaseFactory
import revision.core.data.TopicRepository
import revision.core.systemNow
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders screens off-screen (no window opens) into build/screenshots so the layout can be
 * looked at without touching the desktop.
 */
class RenderTest {
    private val day = 86_400_000L

    private fun render(name: String, db: revision.core.db.RevisionDatabase, phone: Boolean = false, prepare: (AppState) -> Unit = {}) {
        val state = AppState(db)
        prepare(state)
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = if (phone) 900 else 1000, height = if (phone) 1950 else 800, density = Density(if (phone) 2.5f else 1f)) { App(db, NoFileAccess, state = state) }
        try {
            scene.render(0)
            val image = scene.render(1_000_000_000L)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File("build/screenshots/$name.png").also { it.parentFile.mkdirs() }
            out.writeBytes(bytes)
            assertTrue(out.length() > 1000)
        } finally {
            scene.close()
        }
    }

    private fun sampleDb(): revision.core.db.RevisionDatabase {
        val db = DatabaseFactory.inMemory()
        TestSubjects.installAll(db, systemNow)
        val history = HistoryService(db, systemNow)
        val topics = TopicRepository(db, systemNow)
        val bio = topics.getBySubject(TestSubjects.BIOLOGY)
        val phys = topics.getBySubject(TestSubjects.PHYSICS)
        val now = systemNow()
        history.logManual(TestSubjects.BIOLOGY, now - 6 * day, listOf(ManualTopic(bio[0].id, 40, 4)), null)
        history.logManual(TestSubjects.BIOLOGY, now - 4 * day, listOf(ManualTopic(bio[1].id, 25, 2), ManualTopic(bio[4].id, 20, 5)), null)
        history.logManual(TestSubjects.PHYSICS, now - 2 * day, listOf(ManualTopic(phys[0].id, 55, 3)), null)
        history.logManual(TestSubjects.PHYSICS, now - 1 * day, listOf(ManualTopic(phys[1].id, 30, 1)), null)
        history.logManual(TestSubjects.BIOLOGY, now - 60_000L, listOf(ManualTopic(bio[2].id, 45, 5)), null)
        return db
    }

    @Test
    fun logo() {
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = 432, height = 432, density = Density(1f)) {
            androidx.compose.foundation.Image(LogoPainter(), contentDescription = null, modifier = androidx.compose.ui.Modifier.fillMaxSize())
        }
        try {
            scene.render(0)
            val bytes = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots/logo.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        } finally { scene.close() }
    }

    /** A database with nothing in it: what a brand-new install looks like, so setup shows. */
    private fun freshDb() = DatabaseFactory.inMemory()

    @Test
    fun setupScreenShowsOnAFreshInstall() = render("setup-subjects", freshDb())

    @Test
    fun phoneSetupScreen() = render("phone-setup-subjects", freshDb(), phone = true)

    /** Step two, with three subjects ticked, so the per-subject board pickers are visible. */
    @Test
    fun setupBoardsStep() {
        val db = freshDb()
        val state = AppState(db)
        val picked = mapOf(
            "biology" to revision.core.catalogue.Board.AQA,
            "computer-science" to revision.core.catalogue.Board.AQA,
            "history" to revision.core.catalogue.Board.AQA,
        )
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = 1000, height = 800, density = Density(1f)) {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    SetupScreen(
                        state,
                        rememberSyncManager(db, NoSyncPlatform),
                        initialStep = SetupStep.Boards,
                        initialPicked = picked,
                    ) {}
                }
            }
        }
        try {
            scene.render(0)
            val bytes = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots/setup-boards.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        } finally { scene.close() }
    }

    @Test
    fun todayScreen() = render("today", sampleDb())

    @Test
    fun historyScreen() = render("history", sampleDb()) { it.screen = Screen.History }

    @Test
    fun statsScreen() = render("stats", sampleDb()) { it.screen = Screen.Stats }

    @Test
    fun settingsScreen() = render("settings", sampleDb().also {
        revision.core.data.SubjectRepository(it, systemNow).setExamDate(TestSubjects.PHYSICS, systemNow() + 12 * day)
    }) { it.screen = Screen.Settings }

    @Test
    fun topicsScreen() = render("topics", sampleDb()) { it.screen = Screen.Topics; it.manageSubjectId = TestSubjects.BIOLOGY }

    @Test
    fun timerSetupScreen() = render("timer-setup", sampleDb()) { it.screen = Screen.Timer }

    // ---- phone-sized (360 x 780 dp) ----
    @Test fun phoneToday() = render("phone-today", sampleDb(), phone = true)
    @Test fun phoneHistory() = render("phone-history", sampleDb(), phone = true) { it.screen = Screen.History }
    @Test fun phoneTopics() = render("phone-topics", sampleDb(), phone = true) { it.screen = Screen.Topics; it.manageSubjectId = TestSubjects.BIOLOGY }
    @Test fun phoneStats() = render("phone-stats", sampleDb(), phone = true) { it.screen = Screen.Stats }
    @Test fun phoneSettings() = render("phone-settings", sampleDb(), phone = true) { it.screen = Screen.Settings }
    @Test fun phoneTimer() = render("phone-timer", sampleDb(), phone = true) {
        val bio = it.topics.getBySubject(TestSubjects.BIOLOGY)
        it.start(TestSubjects.BIOLOGY, listOf(bio[0].id, bio[4].id))
        it.screen = Screen.Timer
    }

    @Test fun phoneLogPastWithTopicTicked() {
        val db = sampleDb()
        val bio = revision.core.data.TopicRepository(db, systemNow).getBySubject(TestSubjects.BIOLOGY)
        val state = AppState(db)
        state.screen = Screen.LogPast
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = 900, height = 1950, density = Density(2.5f)) {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalCompact provides true) {
                        LogPastScreen(state, initialSubjectId = TestSubjects.BIOLOGY, initialSelected = listOf(bio[0].id, bio[1].id))
                    }
                }
            }
        }
        try {
            scene.render(0)
            val bytes = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots/phone-logpast.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        } finally { scene.close() }
    }

    @Test fun phoneTimerSubjectShowsReviseNextByDefault() =
        render("phone-timer-subject", sampleDb(), phone = true) { it.screen = Screen.Timer; it.timerSubjectId = TestSubjects.BIOLOGY }

    @Test fun phoneTimerGeographyShowsContext() =
        render("phone-timer-geography", sampleDb(), phone = true) { it.screen = Screen.Timer; it.timerSubjectId = TestSubjects.GEOGRAPHY }
}
