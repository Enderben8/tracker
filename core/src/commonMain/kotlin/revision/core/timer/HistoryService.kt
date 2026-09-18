package revision.core.timer

import revision.core.Now
import revision.core.data.SessionRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.scheduling.SrsService
import revision.core.db.RevisionDatabase

data class HistoryTopic(
    val sessionTopicId: String,
    val topicId: String,
    val title: String,
    val rating: Int?,
    val ms: Long,
)

data class HistoryEntry(
    val sessionId: String,
    val subjectId: String,
    val subjectName: String,
    val subjectColour: String,
    val startedAt: Long,
    val endedAt: Long?,
    val isManual: Boolean,
    val notes: String?,
    val topics: List<HistoryTopic>,
) {
    val totalMs: Long get() = topics.sumOf { it.ms }
}

data class ManualTopic(val topicId: String, val minutes: Int, val rating: Int? = null)

class HistoryService(private val db: RevisionDatabase, private val now: Now) {
    private val sessions = SessionRepository(db, now)
    private val subjects = SubjectRepository(db, now)
    private val topics = TopicRepository(db, now)
    private val srs = SrsService(db, now)

    /** Finished sessions, newest first. The running session is excluded. */
    fun load(): List<HistoryEntry> {
        val nowMillis = now()
        return sessions.finishedSessions().map { s ->
            val subject = subjects.get(s.subject_id)
            val segments = sessions.getSegments(s.id)
            HistoryEntry(
                sessionId = s.id,
                subjectId = s.subject_id,
                subjectName = subject?.name ?: "(removed subject)",
                subjectColour = subject?.colour ?: "#888888",
                startedAt = s.started_at,
                endedAt = s.ended_at,
                isManual = s.is_manual == 1L,
                notes = s.notes,
                topics = sessions.getSessionTopics(s.id).map { st ->
                    HistoryTopic(
                        sessionTopicId = st.id,
                        topicId = st.topic_id,
                        title = topics.get(st.topic_id)?.title ?: "(removed topic)",
                        rating = st.rating?.toInt(),
                        ms = segments.filter { it.session_topic_id == st.id }
                            .sumOf { (it.ended_at ?: nowMillis) - it.started_at },
                    )
                },
            )
        }
    }

    fun delete(sessionId: String) = sessions.deleteSession(sessionId)

    fun setNotes(sessionId: String, notes: String?) {
        val s = sessions.getSession(sessionId) ?: return
        sessions.finishSession(sessionId, s.ended_at ?: s.started_at, notes)
    }

    fun setRating(sessionTopicId: String, rating: Int?) = sessions.rateTopic(sessionTopicId, rating?.toLong(), null)

    /**
     * Fixes a wrong duration: replaces the topic's segments with a single one of the given
     * length, and re-derives the session end so the session still matches its topics.
     */
    fun setTopicMinutes(sessionId: String, sessionTopicId: String, minutes: Int) {
        val s = sessions.getSession(sessionId) ?: return
        db.transaction {
            sessions.softDeleteSegments(sessionTopicId)
            sessions.addClosedSegment(sessionTopicId, s.started_at, s.started_at + minutes * 60_000L)
            val total = sessions.getSegments(sessionId).sumOf { (it.ended_at ?: it.started_at) - it.started_at }
            sessions.finishSession(sessionId, s.started_at + total, s.notes)
        }
    }

    /**
     * "Log a past session": one session, one synthesised segment per topic laid end to end,
     * so the totals queries need no special case for manual entries.
     */
    fun logManual(subjectId: String, startedAt: Long, entries: List<ManualTopic>, notes: String?): String {
        require(entries.isNotEmpty()) { "Pick at least one topic" }
        var sessionId = ""
        db.transaction {
            sessionId = sessions.createSession(subjectId, startedAt, isManual = true)
            var cursor = startedAt
            entries.forEach { e ->
                val st = sessions.addTopicToSession(sessionId, e.topicId)
                val end = cursor + e.minutes * 60_000L
                sessions.addClosedSegment(st, cursor, end)
                if (e.rating != null) sessions.rateTopic(st, e.rating.toLong(), null)
                cursor = end
            }
            sessions.finishSession(sessionId, cursor, notes)
            srs.applySession(sessionId, cursor)
        }
        return sessionId
    }
}
