package revision.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import revision.core.Now
import revision.core.db.RevisionDatabase
import revision.core.db.Segment
import revision.core.db.Session
import revision.core.db.Session_topic
import revision.core.db.Subject
import revision.core.db.Topic
import revision.core.db.Topic_state
import revision.core.newId

// A Flow is a stream of values over time. Each `observe...` function below emits the current
// list straight away, then emits a fresh list every time the underlying table changes, so
// the UI can just collect it and redraw. "Archiving" something means soft-deleting it
// (deleted = 1): the row stays in the database so history still makes sense and sync can
// tell "deleted" apart from "never seen".

class SubjectRepository(private val db: RevisionDatabase, private val now: Now) {
    private val q = db.subjectQueries

    fun observeAll(): Flow<List<Subject>> = q.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun getAll(): List<Subject> = q.selectAll().executeAsList()

    fun get(id: String): Subject? = q.selectById(id).executeAsOneOrNull()

    fun add(name: String, colour: String, examBoard: String? = null): String {
        val id = newId()
        val nextOrder = (q.selectAll().executeAsList().maxOfOrNull { it.sort_order } ?: -1L) + 1
        q.insert(id, name, colour, examBoard, null, nextOrder, now(), 0L)
        return id
    }

    fun update(subject: Subject) {
        q.update(subject.name, subject.colour, subject.exam_board, subject.exam_date, subject.sort_order, now(), subject.id)
    }

    fun setExamDate(id: String, examDateMillis: Long?) = q.setExamDate(examDateMillis, now(), id)

    fun archive(id: String) = q.softDelete(now(), id)

    fun restore(id: String) = q.restore(now(), id)
}

class TopicRepository(private val db: RevisionDatabase, private val now: Now) {
    private val q = db.topicQueries

    fun observeAll(): Flow<List<Topic>> = q.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun observeBySubject(subjectId: String): Flow<List<Topic>> =
        q.selectBySubject(subjectId).asFlow().mapToList(Dispatchers.Default)

    fun getBySubject(subjectId: String): List<Topic> = q.selectBySubject(subjectId).executeAsList()

    fun get(id: String): Topic? = q.selectById(id).executeAsOneOrNull()

    fun add(subjectId: String, parentId: String?, title: String, code: String? = null, pageStart: Long? = null): String {
        val id = newId()
        val siblings = q.selectBySubject(subjectId).executeAsList().filter { it.parent_id == parentId }
        val nextOrder = (siblings.maxOfOrNull { it.sort_order } ?: -1L) + 1
        q.insert(id, subjectId, parentId, code, title, pageStart, nextOrder, null, now(), 0L)
        return id
    }

    fun update(topic: Topic) {
        q.update(topic.parent_id, topic.code, topic.title, topic.page_start, topic.sort_order, topic.notes, now(), topic.id)
    }

    /** Archives the topic and everything nested under it. */
    fun archive(id: String) {
        val topic = q.selectById(id).executeAsOneOrNull() ?: return
        val all = q.selectBySubject(topic.subject_id).executeAsList()
        val ids = mutableListOf(id)
        var frontier = listOf(id)
        while (frontier.isNotEmpty()) {
            frontier = all.filter { it.parent_id in frontier }.map { it.id }
            ids += frontier
        }
        val timestamp = now()
        db.transaction { ids.forEach { q.softDelete(timestamp, it) } }
    }

    fun restore(id: String) = q.restore(now(), id)
}

class TopicStateRepository(private val db: RevisionDatabase) {
    private val q = db.topicStateQueries

    fun observeAll(): Flow<List<Topic_state>> = q.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun getAll(): List<Topic_state> = q.selectAll().executeAsList()

    fun get(topicId: String): Topic_state? = q.selectByTopic(topicId).executeAsOneOrNull()

    fun save(state: Topic_state) {
        q.upsert(
            state.topic_id, state.last_revised_at, state.due_at, state.interval_days,
            state.ease_factor, state.repetitions, state.confidence, state.updated_at, state.deleted,
        )
    }
}

/**
 * Low-level access to sessions, the topics covered in them, and their timed segments.
 * The timer logic that orchestrates these (start, pause, switch topic, stop) is Phase 2.
 */
class SessionRepository(private val db: RevisionDatabase, private val now: Now) {
    private val q = db.sessionQueries

    fun observeAllSessions(): Flow<List<Session>> = q.selectAllSessions().asFlow().mapToList(Dispatchers.Default)

    fun observeAllSessionTopics(): Flow<List<Session_topic>> =
        q.selectAllSessionTopics().asFlow().mapToList(Dispatchers.Default)

    /** Milliseconds per topic id. Open segments count up to [nowMillis]. */
    fun totalsByTopic(nowMillis: Long): Map<String, Long> =
        q.totalsByTopic(nowMillis).executeAsList().associate { it.topic_id to (it.total_ms ?: 0L) }

    fun observeTotalsByTopic(nowMillis: Long): Flow<Map<String, Long>> =
        q.totalsByTopic(nowMillis).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.associate { it.topic_id to (it.total_ms ?: 0L) } }

    fun lastRevisedByTopic(): Map<String, Long> =
        q.lastRevisedByTopic().executeAsList().associate { it.topic_id to (it.last_at ?: 0L) }

    fun getSession(id: String): Session? = q.selectSessionById(id).executeAsOneOrNull()

    fun getSessionTopics(sessionId: String): List<Session_topic> = q.selectSessionTopics(sessionId).executeAsList()

    fun getSegments(sessionId: String): List<Segment> = q.selectSegmentsForSession(sessionId).executeAsList()

    /** Sessions that never got an end time: running now, or left dangling by a crash. */
    fun unfinishedSessions(): List<Session> = q.selectUnfinishedSessions().executeAsList()

    fun finishedSessions(): List<Session> = q.selectAllSessions().executeAsList().filter { it.ended_at != null }

    fun softDeleteSegments(sessionTopicId: String) = q.softDeleteSegmentsForSessionTopic(now(), sessionTopicId)

    fun openSegments(): List<Segment> = q.selectOpenSegments().executeAsList()

    fun createSession(subjectId: String, startedAt: Long, isManual: Boolean = false): String {
        val id = newId()
        q.insertSession(id, subjectId, startedAt, null, null, if (isManual) 1L else 0L, now(), 0L)
        return id
    }

    fun addTopicToSession(sessionId: String, topicId: String): String {
        val id = newId()
        q.insertSessionTopic(id, sessionId, topicId, null, null, now(), 0L)
        return id
    }

    fun openSegment(sessionTopicId: String, startedAt: Long): String {
        val id = newId()
        q.insertSegment(id, sessionTopicId, startedAt, null, now(), 0L)
        return id
    }

    fun closeSegment(segmentId: String, endedAt: Long) = q.closeSegment(endedAt, now(), segmentId)

    fun finishSession(sessionId: String, endedAt: Long, notes: String?) = q.finishSession(endedAt, notes, now(), sessionId)

    fun rateTopic(sessionTopicId: String, rating: Long?, notes: String?) =
        q.setSessionTopicRating(rating, notes, now(), sessionTopicId)

    /** Adds a finished segment in one go (used by manual "log a past session" entry). */
    fun addClosedSegment(sessionTopicId: String, startedAt: Long, endedAt: Long): String {
        val id = newId()
        q.insertSegment(id, sessionTopicId, startedAt, endedAt, now(), 0L)
        return id
    }

    fun deleteSession(sessionId: String) {
        val timestamp = now()
        db.transaction {
            q.selectSessionTopics(sessionId).executeAsList().forEach {
                q.softDeleteSegmentsForSessionTopic(timestamp, it.id)
                q.softDeleteSessionTopic(timestamp, it.id)
            }
            q.softDeleteSession(timestamp, sessionId)
        }
    }
}

class SettingsRepository(private val db: RevisionDatabase, private val now: Now) {
    private val q = db.settingQueries

    fun get(key: String): String? = q.selectByKey(key).executeAsOneOrNull()

    fun put(key: String, value: String) = q.put(key, value, now())

    fun observe(key: String): Flow<String?> = q.selectByKey(key).asFlow().mapToOneOrNull(Dispatchers.Default)
}
