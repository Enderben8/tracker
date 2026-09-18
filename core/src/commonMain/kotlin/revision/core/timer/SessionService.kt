package revision.core.timer

import revision.core.Now
import revision.core.db.RevisionDatabase
import revision.core.db.Session
import revision.core.data.SessionRepository
import revision.core.data.SettingsRepository
import revision.core.data.TopicRepository
import revision.core.scheduling.SchedulerConfigStore
import revision.core.scheduling.SrsService

data class ActiveTopic(
    val sessionTopicId: String,
    val topicId: String,
    val title: String,
    val totalMs: Long,
)

/**
 * A session that has not been stopped yet. Everything here is derived from the stored
 * segments, so elapsed time is computed from timestamps (never a counter that can drift
 * when the machine sleeps).
 */
data class ActiveSession(
    val sessionId: String,
    val subjectId: String,
    val startedAt: Long,
    val topics: List<ActiveTopic>,
    /** The session_topic whose clock is running right now, or null when paused. */
    val runningSessionTopicId: String?,
    /** The topic that was running most recently; what "resume" returns to. */
    val lastSessionTopicId: String?,
) {
    val isPaused: Boolean get() = runningSessionTopicId == null
    val totalMs: Long get() = topics.sumOf { it.totalMs }
}

/** A session left running when the app died. Never silently discarded or counted. */
data class DanglingSession(
    val sessionId: String,
    val subjectId: String,
    val startedAt: Long,
    /** When the open segment began. */
    val segmentStartedAt: Long,
    /** Last moment we know the app was alive (heartbeat), never before the segment began. */
    val lastAliveAt: Long,
    /** True when the gap is implausibly long, so the dialog should default to discarding. */
    val implausiblyLong: Boolean,
)

data class TopicRating(val rating: Int?, val notes: String? = null)

class SessionService(
    private val db: RevisionDatabase,
    private val now: Now,
    private val maxPlausibleMs: Long = 4 * 60 * 60 * 1000L,
) {
    private val sessions = SessionRepository(db, now)
    private val topics = TopicRepository(db, now)
    private val settings = SettingsRepository(db, now)
    private val srs = SrsService(db, now, SchedulerConfigStore(SettingsRepository(db, now))::load)

    // ---------- reading state ----------

    fun active(nowMillis: Long = now()): ActiveSession? =
        sessions.unfinishedSessions().firstOrNull()?.let { build(it, nowMillis) }

    private fun build(s: Session, nowMillis: Long): ActiveSession {
        val sessionTopics = sessions.getSessionTopics(s.id)
        val segments = sessions.getSegments(s.id)
        val out = sessionTopics.map { st ->
            val total = segments.filter { it.session_topic_id == st.id }
                .sumOf { (it.ended_at ?: nowMillis) - it.started_at }
            ActiveTopic(st.id, st.topic_id, topics.get(st.topic_id)?.title ?: "(removed topic)", total)
        }
        return ActiveSession(
            sessionId = s.id,
            subjectId = s.subject_id,
            startedAt = s.started_at,
            topics = out,
            runningSessionTopicId = segments.firstOrNull { it.ended_at == null }?.session_topic_id,
            lastSessionTopicId = segments.maxByOrNull { it.started_at }?.session_topic_id
                ?: sessionTopics.firstOrNull()?.id,
        )
    }

    // ---------- the live session ----------

    /** Starts a session on the given topics (all from one subject); the first topic runs. */
    fun start(subjectId: String, topicIds: List<String>, at: Long = now()): String {
        require(topicIds.isNotEmpty()) { "Pick at least one topic" }
        check(sessions.unfinishedSessions().isEmpty()) { "A session is already in progress" }
        var sessionId = ""
        db.transaction {
            sessionId = sessions.createSession(subjectId, at)
            val ids = topicIds.distinct().map { sessions.addTopicToSession(sessionId, it) }
            sessions.openSegment(ids.first(), at)
        }
        return sessionId
    }

    /** Makes [sessionTopicId] the running topic without stopping the clock. */
    fun switchTo(sessionTopicId: String, at: Long = now()) {
        val open = sessions.openSegments().firstOrNull()
        if (open?.session_topic_id == sessionTopicId) return
        db.transaction {
            open?.let { sessions.closeSegment(it.id, at) }
            sessions.openSegment(sessionTopicId, at)
        }
    }

    fun pause(at: Long = now()) {
        sessions.openSegments().forEach { sessions.closeSegment(it.id, at) }
    }

    fun resume(at: Long = now()) {
        val session = active(at) ?: return
        if (!session.isPaused) return
        val target = session.lastSessionTopicId ?: return
        sessions.openSegment(target, at)
    }

    /** Adds a topic mid-session. Returns its session_topic id (existing one if already added). */
    fun addTopic(sessionId: String, topicId: String, makeActive: Boolean = true, at: Long = now()): String {
        val existing = sessions.getSessionTopics(sessionId).firstOrNull { it.topic_id == topicId }
        val stId = existing?.id ?: sessions.addTopicToSession(sessionId, topicId)
        if (makeActive) switchTo(stId, at)
        return stId
    }

    /** Stops the session, saving ratings (a missing rating is fine — it is skippable). */
    fun stop(
        sessionId: String,
        ratings: Map<String, TopicRating> = emptyMap(),
        sessionNotes: String? = null,
        at: Long = now(),
    ) {
        db.transaction {
            sessions.openSegments().forEach { sessions.closeSegment(it.id, at) }
            ratings.forEach { (stId, r) -> sessions.rateTopic(stId, r.rating?.toLong(), r.notes) }
            sessions.finishSession(sessionId, at, sessionNotes)
            srs.applySession(sessionId, at)
        }
    }

    fun discard(sessionId: String) = sessions.deleteSession(sessionId)

    // ---------- heartbeat & crash recovery ----------

    /** Called periodically while a session runs, so a crash can be dated honestly. */
    fun heartbeat(at: Long = now()) = settings.put(HEARTBEAT_KEY, at.toString())

    /** Non-null if the app died while a segment was running. */
    fun findDangling(at: Long = now()): DanglingSession? {
        val open = sessions.openSegments().firstOrNull() ?: return null
        val st = sessions.getSessionTopics(sessions.unfinishedSessions().firstOrNull()?.id ?: return null)
            .firstOrNull { it.id == open.session_topic_id } ?: return null
        val session = sessions.getSession(st.session_id) ?: return null
        val heartbeat = settings.get(HEARTBEAT_KEY)?.toLongOrNull() ?: open.started_at
        val lastAlive = heartbeat.coerceAtLeast(open.started_at)
        return DanglingSession(
            sessionId = session.id,
            subjectId = session.subject_id,
            startedAt = session.started_at,
            segmentStartedAt = open.started_at,
            lastAliveAt = lastAlive,
            implausiblyLong = at - open.started_at > maxPlausibleMs,
        )
    }

    /** Closes the dangling segment at the last known-alive time; the session stays paused. */
    fun recoverAsPaused(d: DanglingSession) {
        sessions.openSegments().forEach { sessions.closeSegment(it.id, d.lastAliveAt) }
    }

    /** Closes the dangling segment at the last known-alive time and finishes the session. */
    fun recoverAndSave(d: DanglingSession) {
        db.transaction {
            sessions.openSegments().forEach { sessions.closeSegment(it.id, d.lastAliveAt) }
            sessions.finishSession(d.sessionId, d.lastAliveAt, null)
            srs.applySession(d.sessionId, d.lastAliveAt)
        }
    }

    companion object {
        const val HEARTBEAT_KEY = "heartbeat"
    }
}
