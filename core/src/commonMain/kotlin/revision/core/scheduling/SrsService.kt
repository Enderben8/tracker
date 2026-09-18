package revision.core.scheduling

import revision.core.Now
import revision.core.data.SessionRepository
import revision.core.data.TopicStateRepository
import revision.core.db.RevisionDatabase
import revision.core.db.Topic_state

/** Writes spaced-repetition state after a session ends. */
class SrsService(
    db: RevisionDatabase,
    private val now: Now,
    private val configProvider: () -> SchedulerConfig = { SchedulerConfig() },
) {
    private val sessions = SessionRepository(db, now)
    private val states = TopicStateRepository(db)

    /**
     * Rated topics get the SM-2 update; topics covered without a rating only get their
     * "last revised" date moved. Topics that were added but never actually timed are ignored.
     */
    fun applySession(sessionId: String, at: Long) {
        val config = configProvider()
        val segments = sessions.getSegments(sessionId)
        for (st in sessions.getSessionTopics(sessionId)) {
            val timed = segments.filter { it.session_topic_id == st.id }
                .sumOf { (it.ended_at ?: at) - it.started_at }
            if (timed <= 0L) continue

            val existingRow = states.get(st.topic_id)
            val existing = existingRow?.toSrs()
            // A session logged for the past must not overwrite state from a newer one.
            if (existing?.lastRevisedAt != null && existing.lastRevisedAt > at) continue

            val updated = if (st.rating != null) Sm2.review(existing, st.rating.toInt(), at, config)
            else Sm2.touch(existing, at, config)
            states.save(updated.toRow(st.topic_id, existingRow?.confidence, now()))
        }
    }
}

fun Topic_state.toSrs() = SrsState(
    lastRevisedAt = last_revised_at,
    dueAt = due_at,
    intervalDays = interval_days,
    easeFactor = ease_factor,
    repetitions = repetitions.toInt(),
)

private fun SrsState.toRow(topicId: String, confidence: Long?, updatedAt: Long) = Topic_state(
    topic_id = topicId,
    last_revised_at = lastRevisedAt,
    due_at = dueAt,
    interval_days = intervalDays,
    ease_factor = easeFactor,
    repetitions = repetitions.toLong(),
    confidence = confidence,
    updated_at = updatedAt,
    deleted = 0L,
)
