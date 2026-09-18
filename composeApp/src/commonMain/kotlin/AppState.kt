import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.db.RevisionDatabase
import revision.core.scheduling.TodayService
import revision.core.systemNow
import revision.core.timer.ActiveSession
import revision.core.timer.DanglingSession
import revision.core.timer.HistoryService
import revision.core.timer.SessionService

enum class Screen { Today, Timer, History, LogPast }

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
    val today = TodayService(db, now)

    var screen by mutableStateOf(Screen.Today)
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

    fun stop(ratings: Map<String, revision.core.timer.TopicRating>, notes: String?) {
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
}

enum class DanglingAction { Resume, Save, Discard }

fun parseColour(hex: String): Color =
    runCatching { Color(0xFF000000L or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color.Gray)
