package revision.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import revision.core.backup.BackupService
import revision.core.catalogue.CatalogueInstaller
import revision.core.data.SettingsRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.db.RevisionDatabase
import revision.core.manage.SubjectEditor
import revision.core.manage.TopicEditor
import revision.core.scheduling.SchedulerConfigStore
import revision.core.scheduling.TodayService
import revision.core.stats.StatsService
import revision.core.systemNow
import revision.core.timer.ActiveSession
import revision.core.timer.DanglingSession
import revision.core.timer.HistoryService
import revision.core.timer.SessionService
import revision.core.timer.TopicRating

enum class Screen { Today, Timer, History, LogPast, Topics, Stats, Settings }

/** Which part of the first-run wizard is showing. */
enum class SetupStep { Subjects, Boards, Options, Review }

/**
 * Holds everything the screens share. Compose re-draws whenever a `by mutableStateOf`
 * property below changes. The running session itself lives in the database, so this
 * object can be thrown away and rebuilt (e.g. after a crash) without losing anything.
 */
class AppState(val db: RevisionDatabase) {
    private val now = systemNow
    val sessions = SessionService(db, now)
    val history = HistoryService(db, now)
    val subjects = SubjectRepository(db, now)
    val topics = TopicRepository(db, now)
    val settings = SettingsRepository(db, now)
    val schedulerConfig = SchedulerConfigStore(settings)
    val today = TodayService(db, now, schedulerConfig::load)
    val topicEditor = TopicEditor(db, now)
    val subjectEditor = SubjectEditor(db, now)
    val stats = StatsService(db, now)
    val backup = BackupService(db, now)

    var screen by mutableStateOf(Screen.Today)
    /**
     * True until subjects have been chosen. Kept as a setting rather than "are there any
     * subjects?", so archiving everything does not send you back to the wizard — and because it
     * syncs, a second device that receives your subjects skips setup too.
     */
    var needsSetup by mutableStateOf(!CatalogueInstaller.isSetUp(settings))
        private set
    /** The subject chosen on the Topics screen; kept here so it survives switching tabs. */
    var manageSubjectId by mutableStateOf<String?>(null)
    /** The subject chosen on the Timer screen; kept so it survives switching tabs. */
    var timerSubjectId by mutableStateOf<String?>(null)
    var active by mutableStateOf<ActiveSession?>(null)
        private set
    var dangling by mutableStateOf<DanglingSession?>(null)
        private set
    /** Updated once a second; screens read it so they redraw and show a live clock. */
    var tick by mutableStateOf(now())
        private set
    /** Bumped whenever history may have changed, so the History screen reloads. */
    var historyVersion by mutableStateOf(0)
        private set

    init {
        dangling = sessions.findDangling()
        if (dangling == null) active = sessions.active()
    }

    fun onTick() {
        tick = now()
        if (dangling == null) active = sessions.active(tick)
    }

    fun heartbeat() {
        if (active?.isPaused == false) sessions.heartbeat()
    }

    private fun changed() {
        tick = now()
        active = sessions.active(tick)
        needsSetup = !CatalogueInstaller.isSetUp(settings)
        historyVersion++
    }

    fun start(subjectId: String, topicIds: List<String>) { sessions.start(subjectId, topicIds); changed() }
    /** One tap from the "revise next" list: start a session on the topic, or add it to the running one. */
    fun startOrAdd(subjectId: String, topicId: String) {
        val running = active
        if (running == null) start(subjectId, listOf(topicId))
        else if (running.subjectId == subjectId) addTopics(listOf(topicId))
        else return
        screen = Screen.Timer
    }

    fun switchTo(stId: String) { sessions.switchTo(stId); changed() }
    fun pause() { sessions.pause(); changed() }
    fun resume() { sessions.resume(); changed() }

    fun addTopics(topicIds: List<String>) {
        val s = active ?: return
        topicIds.forEachIndexed { i, id -> sessions.addTopic(s.sessionId, id, makeActive = i == 0) }
        changed()
    }

    fun stop(ratings: Map<String, TopicRating>, notes: String?) {
        val s = active ?: return
        sessions.stop(s.sessionId, ratings, notes)
        changed()
    }

    fun discardActive() { active?.let { sessions.discard(it.sessionId) }; changed() }

    fun resolveDangling(action: DanglingAction) {
        val d = dangling ?: return
        when (action) {
            DanglingAction.Resume -> sessions.recoverAsPaused(d)
            DanglingAction.Save -> sessions.recoverAndSave(d)
            DanglingAction.Discard -> sessions.discard(d.sessionId)
        }
        dangling = null
        changed()
    }

    fun deleteHistory(sessionId: String) { history.delete(sessionId); changed() }
    fun historyChanged() { changed() }

    /** Call after anything that edits subjects, topics, settings or imports data. */
    fun dataChanged() { changed() }
}

enum class DanglingAction { Resume, Save, Discard }

fun parseColour(hex: String): Color =
    runCatching { Color(0xFF000000L or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color.Gray)
