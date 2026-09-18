package revision.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import revision.core.Now
import revision.core.db.RevisionDatabase

@Serializable data class SubjectRow(
    val id: String, val name: String, val colour: String, val examBoard: String?, val examDate: Long?,
    val sortOrder: Long, val updatedAt: Long, val deleted: Long,
)

@Serializable data class TopicRow(
    val id: String, val subjectId: String, val parentId: String?, val code: String?, val title: String,
    val pageStart: Long?, val sortOrder: Long, val notes: String?, val updatedAt: Long, val deleted: Long,
)

@Serializable data class TopicStateRow(
    val topicId: String, val lastRevisedAt: Long?, val dueAt: Long?, val intervalDays: Double,
    val easeFactor: Double, val repetitions: Long, val confidence: Long?, val updatedAt: Long, val deleted: Long,
)

@Serializable data class SessionRow(
    val id: String, val subjectId: String, val startedAt: Long, val endedAt: Long?, val notes: String?,
    val isManual: Long, val updatedAt: Long, val deleted: Long,
)

@Serializable data class SessionTopicRow(
    val id: String, val sessionId: String, val topicId: String, val rating: Long?, val notes: String?,
    val updatedAt: Long, val deleted: Long,
)

@Serializable data class SegmentRow(
    val id: String, val sessionTopicId: String, val startedAt: Long, val endedAt: Long?,
    val updatedAt: Long, val deleted: Long,
)

@Serializable data class SettingRow(val key: String, val value: String, val updatedAt: Long)

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val subjects: List<SubjectRow>,
    val topics: List<TopicRow>,
    val topicStates: List<TopicStateRow>,
    val sessions: List<SessionRow>,
    val sessionTopics: List<SessionTopicRow>,
    val segments: List<SegmentRow>,
    val settings: List<SettingRow>,
) {
    companion object { const val FORMAT = "revision-tracker-backup" }
}

data class ImportResult(val added: Int, val updated: Int, val unchanged: Int, val skipped: Int) {
    override fun toString() = "$added added, $updated updated, $unchanged already up to date, $skipped skipped"
}

/**
 * Whole-database JSON export and import. Import is a MERGE, not a replace: rows are matched by
 * id and the one with the newer `updated_at` wins (ties keep what is already here). Deleted
 * rows travel too, so an archive on one copy is not undone by importing an older one.
 * A session that is still running is not exported, so importing can't create a phantom timer.
 */
class BackupService(private val db: RevisionDatabase, private val now: Now) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val compactJson = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

    /** [pretty] = human-readable (for files you keep); false = compact (for automatic snapshots, ~35% smaller). */
    fun export(pretty: Boolean = true): String {
        val sessions = db.sessionQueries.exportSessions().executeAsList().filter { it.ended_at != null }
        val sessionIds = sessions.map { it.id }.toSet()
        val sessionTopics = db.sessionQueries.exportSessionTopics().executeAsList().filter { it.session_id in sessionIds }
        val stIds = sessionTopics.map { it.id }.toSet()
        val file = BackupFile(
            exportedAt = now(),
            subjects = db.subjectQueries.exportAll().executeAsList().map {
                SubjectRow(it.id, it.name, it.colour, it.exam_board, it.exam_date, it.sort_order, it.updated_at, it.deleted)
            },
            topics = db.topicQueries.exportAll().executeAsList().map {
                TopicRow(it.id, it.subject_id, it.parent_id, it.code, it.title, it.page_start, it.sort_order, it.notes, it.updated_at, it.deleted)
            },
            topicStates = db.topicStateQueries.exportAll().executeAsList().map {
                TopicStateRow(it.topic_id, it.last_revised_at, it.due_at, it.interval_days, it.ease_factor, it.repetitions, it.confidence, it.updated_at, it.deleted)
            },
            sessions = sessions.map { SessionRow(it.id, it.subject_id, it.started_at, it.ended_at, it.notes, it.is_manual, it.updated_at, it.deleted) },
            sessionTopics = sessionTopics.map { SessionTopicRow(it.id, it.session_id, it.topic_id, it.rating, it.notes, it.updated_at, it.deleted) },
            segments = db.sessionQueries.exportSegments().executeAsList().filter { it.session_topic_id in stIds }
                .map { SegmentRow(it.id, it.session_topic_id, it.started_at, it.ended_at, it.updated_at, it.deleted) },
            settings = db.settingQueries.exportAll().executeAsList()
                .filter { !revision.core.sync.DeviceSettings.isLocal(it.key) }
                .map { SettingRow(it.key, it.value_, it.updated_at) },
        )
        return (if (pretty) json else compactJson).encodeToString(BackupFile.serializer(), file)
    }

    /** Throws [IllegalArgumentException] with a readable message if the text is not one of our backups. */
    fun import(text: String): ImportResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like a Revision Tracker backup file.")
        }
        require(file.format == BackupFile.FORMAT) { "That doesn't look like a Revision Tracker backup file." }
        require(file.version == 1) { "This backup was made by a newer version of the app (format ${file.version})." }

        var added = 0; var updated = 0; var unchanged = 0; var skipped = 0
        fun tally(existing: Long?, incoming: Long): Boolean {
            // returns true if the incoming row should be written
            return when {
                existing == null -> { added++; true }
                incoming > existing -> { updated++; true }
                else -> { unchanged++; false }
            }
        }

        db.transaction {
            val subjectQ = db.subjectQueries
            val subjects = subjectQ.exportAll().executeAsList().associateBy { it.id }
            file.subjects.forEach { r ->
                if (tally(subjects[r.id]?.updated_at, r.updatedAt)) {
                    subjectQ.insert(r.id, r.name, r.colour, r.examBoard, r.examDate, r.sortOrder, r.updatedAt, r.deleted)
                }
            }
            val knownSubjects = subjectQ.exportAll().executeAsList().map { it.id }.toMutableSet()

            // Parents must exist before their children (foreign keys), so insert topics in waves.
            val topicQ = db.topicQueries
            val existingTopics = topicQ.exportAll().executeAsList().associateBy { it.id }
            val knownTopics = existingTopics.keys.toMutableSet()
            var pending = file.topics.filter { it.subjectId in knownSubjects }
            skipped += file.topics.size - pending.size
            while (pending.isNotEmpty()) {
                val ready = pending.filter { it.parentId == null || it.parentId in knownTopics }
                if (ready.isEmpty()) { skipped += pending.size; break }
                ready.forEach { r ->
                    if (tally(existingTopics[r.id]?.updated_at, r.updatedAt)) {
                        topicQ.insert(r.id, r.subjectId, r.parentId, r.code, r.title, r.pageStart, r.sortOrder, r.notes, r.updatedAt, r.deleted)
                    }
                    knownTopics += r.id
                }
                pending = pending - ready.toSet()
            }

            val stateQ = db.topicStateQueries
            val states = stateQ.exportAll().executeAsList().associateBy { it.topic_id }
            file.topicStates.forEach { r ->
                if (r.topicId !in knownTopics) { skipped++; return@forEach }
                if (tally(states[r.topicId]?.updated_at, r.updatedAt)) {
                    stateQ.upsert(r.topicId, r.lastRevisedAt, r.dueAt, r.intervalDays, r.easeFactor, r.repetitions, r.confidence, r.updatedAt, r.deleted)
                }
            }

            val sq = db.sessionQueries
            val sessions = sq.exportSessions().executeAsList().associateBy { it.id }
            val knownSessions = sessions.keys.toMutableSet()
            file.sessions.forEach { r ->
                if (r.subjectId !in knownSubjects) { skipped++; return@forEach }
                if (tally(sessions[r.id]?.updated_at, r.updatedAt)) {
                    sq.insertSession(r.id, r.subjectId, r.startedAt, r.endedAt, r.notes, r.isManual, r.updatedAt, r.deleted)
                }
                knownSessions += r.id
            }
            val sessionTopics = sq.exportSessionTopics().executeAsList().associateBy { it.id }
            val knownSt = sessionTopics.keys.toMutableSet()
            file.sessionTopics.forEach { r ->
                if (r.sessionId !in knownSessions || r.topicId !in knownTopics) { skipped++; return@forEach }
                if (tally(sessionTopics[r.id]?.updated_at, r.updatedAt)) {
                    sq.insertSessionTopic(r.id, r.sessionId, r.topicId, r.rating, r.notes, r.updatedAt, r.deleted)
                }
                knownSt += r.id
            }
            val segments = sq.exportSegments().executeAsList().associateBy { it.id }
            file.segments.forEach { r ->
                if (r.sessionTopicId !in knownSt) { skipped++; return@forEach }
                if (tally(segments[r.id]?.updated_at, r.updatedAt)) {
                    sq.insertSegment(r.id, r.sessionTopicId, r.startedAt, r.endedAt, r.updatedAt, r.deleted)
                }
            }

            val settingQ = db.settingQueries
            val settings = settingQ.exportAll().executeAsList().associateBy { it.key }
            file.settings.forEach { r ->
                if (tally(settings[r.key]?.updated_at, r.updatedAt)) settingQ.put(r.key, r.value, r.updatedAt)
            }
        }
        return ImportResult(added, updated, unchanged, skipped)
    }
}
