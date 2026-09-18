package revision.core.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import revision.core.Now
import revision.core.backup.BackupService
import revision.core.backup.SegmentRow
import revision.core.backup.SessionRow
import revision.core.backup.SessionTopicRow
import revision.core.backup.SettingRow
import revision.core.backup.SubjectRow
import revision.core.backup.TopicRow
import revision.core.backup.TopicStateRow
import revision.core.backup.toRow
import revision.core.backup.write
import revision.core.data.SettingsRepository
import revision.core.db.RevisionDatabase

@Serializable
data class SyncRecord(
    val seq: Long,
    val table: String,
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Long,
    val data: JsonElement,
)

@Serializable
data class SyncHeader(val header: Boolean = true, val format: Int = 1, val device: String, val generation: Long)

data class SyncResult(
    /** Rows written to this device's own log. */
    val pushed: Int,
    /** Rows from other devices that changed this device's data. */
    val applied: Int,
    /** Records that could not be applied yet because a row they depend on has not arrived. */
    val waiting: Int,
    val otherDevices: Int,
    val compacted: Boolean,
)

/**
 * Sync over a shared folder (PROJECT_SPEC.md section 9). Each device writes ONLY its own append-only
 * log (`devices/<device-id>.jsonl`) and only reads the others, so no file ever has two writers and
 * the storage provider never has a conflict to resolve. Rows carry `updated_at`; the newer one wins
 * (ties go to the higher device id, so every device settles on the same winner). Deletions are soft,
 * so they travel like any other change. There are no CRDTs and no server.
 */
class SyncEngine(
    private val db: RevisionDatabase,
    private val now: Now,
    private val folder: SyncFolder,
    private val compactBytes: Long = 5_000_000L,
    private val snapshotEveryMs: Long = 24 * 3_600_000L,
    private val snapshotsToKeep: Int = 5,
) {
    private val settings = SettingsRepository(db, now)
    val deviceId: String = DeviceSettings.deviceId(settings)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val tableOrder = listOf("subject", "topic", "topic_state", "session", "session_topic", "segment", "setting")

    /** Pull, then push, then (at most daily) write a snapshot. */
    suspend fun sync(): SyncResult {
        val applied = pull()
        val push = push(applied.keys)
        maybeSnapshot()
        settings.put(DeviceSettings.LAST_OK, now().toString())
        return SyncResult(push.first, applied.count, applied.waiting, applied.devices, push.second)
    }

    // ------------------------------------------------------------------ pull

    private class Applied(val count: Int, val waiting: Int, val devices: Int, val keys: Set<String>)

    private class Incoming(val device: String, val rec: SyncRecord)

    private suspend fun pull(): Applied {
        val cursors = db.syncCursorQueries.selectAll().executeAsList().associateBy { it.device_id }
        val fresh = mutableListOf<Incoming>()
        val newGeneration = mutableMapOf<String, Long>()
        val maxSeq = mutableMapOf<String, Long>()
        var devices = 0

        for (name in folder.list(SyncFolder.DEVICES)) {
            val device = name.substringBefore('.')
            if (device == deviceId || !name.contains(".jsonl")) continue
            val text = folder.read(SyncFolder.DEVICES, name) ?: continue
            devices++
            val (header, records) = parse(text)
            val generation = header?.generation ?: 0L
            val cursor = cursors[device]
            // A compacted log starts a new generation: its sequence numbers can't be compared with
            // what we read before, so start again from the beginning.
            val lastSeq = if (cursor == null || cursor.generation != generation) 0L else cursor.last_seq
            newGeneration[device] = generation
            maxSeq[device] = lastSeq
            records.filter { it.seq > lastSeq }.forEach { fresh += Incoming(device, it) }
        }

        val local = LocalIndex(db)
        var pending = fresh.sortedWith(compareBy({ tableOrder.indexOf(it.rec.table) }, { it.rec.seq }))
        val failedBySeq = mutableMapOf<String, Long>() // device -> lowest seq that could not be applied
        val keys = mutableSetOf<String>()
        var appliedCount = 0

        db.transaction {
            while (pending.isNotEmpty()) {
                val stillPending = mutableListOf<Incoming>()
                var progress = false
                for (inc in pending) {
                    when (apply(inc, local, keys)) {
                        Outcome.APPLIED -> { appliedCount++; progress = true }
                        Outcome.NOOP -> progress = true
                        Outcome.MISSING_PARENT -> stillPending += inc
                    }
                }
                if (!progress) {
                    stillPending.forEach { failedBySeq[it.device] = minOf(failedBySeq[it.device] ?: Long.MAX_VALUE, it.rec.seq) }
                    pending = emptyList()
                } else pending = stillPending
            }
            for ((device, generation) in newGeneration) {
                val seen = fresh.filter { it.device == device }.maxOfOrNull { it.rec.seq } ?: maxSeq.getValue(device)
                // Never move the cursor past a record we could not apply; it is retried next time.
                val stop = failedBySeq[device]?.let { it - 1 }
                val newLast = if (stop != null) maxOf(maxSeq.getValue(device), stop) else maxOf(maxSeq.getValue(device), seen)
                db.syncCursorQueries.upsert(device, newLast, generation)
            }
        }
        val waiting = if (failedBySeq.isEmpty()) 0 else countWaiting(fresh, failedBySeq)
        return Applied(appliedCount, waiting, devices, keys)
    }

    private fun countWaiting(fresh: List<Incoming>, failed: Map<String, Long>) =
        fresh.count { (failed[it.device] ?: Long.MAX_VALUE) <= it.rec.seq }

    private enum class Outcome { APPLIED, NOOP, MISSING_PARENT }

    private fun wins(existing: Long?, incoming: Long, incomingDevice: String) =
        existing == null || incoming > existing || (incoming == existing && incomingDevice > deviceId)

    private fun apply(inc: Incoming, local: LocalIndex, keys: MutableSet<String>): Outcome {
        val rec = inc.rec
        fun <T> decode(s: KSerializer<T>): T? = try { json.decodeFromJsonElement(s, rec.data) } catch (e: Exception) { null }
        val key = "${rec.table}/${rec.id}/${rec.updatedAt}"

        when (rec.table) {
            "subject" -> {
                val r = decode(SubjectRow.serializer()) ?: return Outcome.NOOP
                if (!wins(local.subjects[r.id], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.subjects[r.id] = r.updatedAt
            }
            "topic" -> {
                val r = decode(TopicRow.serializer()) ?: return Outcome.NOOP
                if (r.subjectId !in local.subjects || (r.parentId != null && r.parentId !in local.topics)) return Outcome.MISSING_PARENT
                if (!wins(local.topics[r.id], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.topics[r.id] = r.updatedAt
            }
            "topic_state" -> {
                val r = decode(TopicStateRow.serializer()) ?: return Outcome.NOOP
                if (r.topicId !in local.topics) return Outcome.MISSING_PARENT
                if (!wins(local.topicStates[r.topicId], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.topicStates[r.topicId] = r.updatedAt
            }
            "session" -> {
                val r = decode(SessionRow.serializer()) ?: return Outcome.NOOP
                if (r.subjectId !in local.subjects) return Outcome.MISSING_PARENT
                if (!wins(local.sessions[r.id], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.sessions[r.id] = r.updatedAt
            }
            "session_topic" -> {
                val r = decode(SessionTopicRow.serializer()) ?: return Outcome.NOOP
                if (r.sessionId !in local.sessions || r.topicId !in local.topics) return Outcome.MISSING_PARENT
                if (!wins(local.sessionTopics[r.id], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.sessionTopics[r.id] = r.updatedAt
            }
            "segment" -> {
                val r = decode(SegmentRow.serializer()) ?: return Outcome.NOOP
                if (r.sessionTopicId !in local.sessionTopics) return Outcome.MISSING_PARENT
                if (!wins(local.segments[r.id], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.segments[r.id] = r.updatedAt
            }
            "setting" -> {
                val r = decode(SettingRow.serializer()) ?: return Outcome.NOOP
                if (DeviceSettings.isLocal(r.key)) return Outcome.NOOP
                if (!wins(local.settings[r.key], r.updatedAt, inc.device)) return Outcome.NOOP
                r.write(db); local.settings[r.key] = r.updatedAt
            }
            else -> return Outcome.NOOP // a table from a newer version of the app: ignore it
        }
        keys += key
        return Outcome.APPLIED
    }

    /** Ids and timestamps of everything local, so each incoming record is a cheap comparison. */
    private class LocalIndex(db: RevisionDatabase) {
        val subjects = db.subjectQueries.exportAll().executeAsList().associate { it.id to it.updated_at }.toMutableMap()
        val topics = db.topicQueries.exportAll().executeAsList().associate { it.id to it.updated_at }.toMutableMap()
        val topicStates = db.topicStateQueries.exportAll().executeAsList().associate { it.topic_id to it.updated_at }.toMutableMap()
        val sessions = db.sessionQueries.exportSessions().executeAsList().associate { it.id to it.updated_at }.toMutableMap()
        val sessionTopics = db.sessionQueries.exportSessionTopics().executeAsList().associate { it.id to it.updated_at }.toMutableMap()
        val segments = db.sessionQueries.exportSegments().executeAsList().associate { it.id to it.updated_at }.toMutableMap()
        val settings = db.settingQueries.exportAll().executeAsList().associate { it.key to it.updated_at }.toMutableMap()
    }

    // ------------------------------------------------------------------ push

    private class Outgoing(val table: String, val id: String, val updatedAt: Long, val deleted: Long, val data: JsonElement)

    /** Returns (rows pushed, whether the log was compacted). */
    private suspend fun push(justApplied: Set<String>): Pair<Int, Boolean> {
        // First ever push: every device seeds identical starting topics, so skip rows still exactly
        // as seeded and send only what has changed since.
        val since = settings.get(DeviceSettings.PUSHED_AT)?.toLongOrNull()
            ?: settings.get(DeviceSettings.SEEDED_AT)?.toLongOrNull() ?: -1L
        val changed = collect(since)
        val outgoing = changed.filter { "${it.table}/${it.id}/${it.updatedAt}" !in justApplied }
        if (outgoing.isEmpty()) {
            // Nothing of ours to send, but rows we just pulled must not be re-examined next time.
            changed.maxOfOrNull { it.updatedAt }?.let { settings.put(DeviceSettings.PUSHED_AT, it.toString()) }
            return 0 to false
        }

        var seq = settings.get(DeviceSettings.SEQ)?.toLongOrNull() ?: 0L
        val lines = outgoing.map { o ->
            seq++
            json.encodeToString(SyncRecord.serializer(), SyncRecord(seq, o.table, o.id, o.updatedAt, o.deleted, o.data))
        }

        val ownName = folder.list(SyncFolder.DEVICES).firstOrNull { it.substringBefore('.') == deviceId && it.contains(".jsonl") }
            ?: "$deviceId.jsonl"
        val existing = folder.read(SyncFolder.DEVICES, ownName)
        val generation = existing?.let { parse(it).first?.generation } ?: (settings.get(DeviceSettings.GENERATION)?.toLongOrNull() ?: 0L)
        val base = existing?.trimEnd('\n', '\r')?.takeIf { it.isNotEmpty() } ?: header(generation)
        var text = base + "\n" + lines.joinToString("\n") + "\n"

        var compacted = false
        if (text.length > compactBytes) {
            text = compact(text, generation)
            settings.put(DeviceSettings.GENERATION, (generation + 1).toString())
            compacted = true
        } else {
            settings.put(DeviceSettings.GENERATION, generation.toString())
        }

        folder.write(SyncFolder.DEVICES, ownName, text)
        // Only after the file is safely written do we move our bookmark forward; a crash in
        // between just means a harmless duplicate push next time.
        settings.put(DeviceSettings.SEQ, seq.toString())
        settings.put(DeviceSettings.PUSHED_AT, changed.maxOf { it.updatedAt }.toString())
        return outgoing.size to compacted
    }

    private fun header(generation: Long) =
        json.encodeToString(SyncHeader.serializer(), SyncHeader(device = deviceId, generation = generation))

    /** Keeps only the newest record for each row, and starts a new generation so readers restart. */
    private fun compact(text: String, generation: Long): String {
        val newest = parse(text).second.groupBy { it.table to it.id }.values.map { it.maxBy { r -> r.seq } }.sortedBy { it.seq }
        return (listOf(header(generation + 1)) + newest.map { json.encodeToString(SyncRecord.serializer(), it) }).joinToString("\n") + "\n"
    }

    /**
     * Rows changed since the last push. A session that is still running is never sent (a half-open
     * session arriving elsewhere would look like a phantom timer). When a session finishes, all its
     * topics and segments go with it, even though those rows were written earlier.
     */
    private fun collect(since: Long): List<Outgoing> {
        val out = mutableListOf<Outgoing>()
        fun <T> add(table: String, id: String, updatedAt: Long, deleted: Long, s: KSerializer<T>, row: T) {
            out += Outgoing(table, id, updatedAt, deleted, json.encodeToJsonElement(s, row))
        }
        db.subjectQueries.exportAll().executeAsList().filter { it.updated_at > since }
            .forEach { add("subject", it.id, it.updated_at, it.deleted, SubjectRow.serializer(), it.toRow()) }
        db.topicQueries.exportAll().executeAsList().filter { it.updated_at > since }
            .forEach { add("topic", it.id, it.updated_at, it.deleted, TopicRow.serializer(), it.toRow()) }
        db.topicStateQueries.exportAll().executeAsList().filter { it.updated_at > since }
            .forEach { add("topic_state", it.topic_id, it.updated_at, it.deleted, TopicStateRow.serializer(), it.toRow()) }

        val finished = db.sessionQueries.exportSessions().executeAsList().filter { it.ended_at != null }
        val finishedIds = finished.map { it.id }.toSet()
        val changedSessions = finished.filter { it.updated_at > since }.map { it.id }.toSet()
        finished.filter { it.id in changedSessions }
            .forEach { add("session", it.id, it.updated_at, it.deleted, SessionRow.serializer(), it.toRow()) }

        val sts = db.sessionQueries.exportSessionTopics().executeAsList()
            .filter { it.session_id in finishedIds && (it.updated_at > since || it.session_id in changedSessions) }
        sts.forEach { add("session_topic", it.id, it.updated_at, it.deleted, SessionTopicRow.serializer(), it.toRow()) }
        val stSession = db.sessionQueries.exportSessionTopics().executeAsList().associate { it.id to it.session_id }
        db.sessionQueries.exportSegments().executeAsList()
            .filter { seg ->
                val session = stSession[seg.session_topic_id]
                session != null && session in finishedIds && (seg.updated_at > since || session in changedSessions)
            }
            .forEach { add("segment", it.id, it.updated_at, it.deleted, SegmentRow.serializer(), it.toRow()) }

        db.settingQueries.exportAll().executeAsList()
            .filter { !DeviceSettings.isLocal(it.key) && it.updated_at > since }
            .forEach { add("setting", it.key, it.updated_at, 0L, SettingRow.serializer(), it.toRow()) }
        return out
    }

    private fun revision.core.db.Setting.toRow() = SettingRow(key, value_, updated_at)

    // ------------------------------------------------------------------ parse

    private fun parse(text: String): Pair<SyncHeader?, List<SyncRecord>> {
        var header: SyncHeader? = null
        val records = mutableListOf<SyncRecord>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            try {
                val obj: JsonObject = json.parseToJsonElement(line).jsonObject
                if ("header" in obj) header = json.decodeFromJsonElement(SyncHeader.serializer(), obj)
                else records += json.decodeFromJsonElement(SyncRecord.serializer(), obj)
            } catch (e: Exception) {
                // A torn or garbled line (e.g. a half-synced file) is skipped, not fatal.
            }
        }
        return header to records
    }

    // ------------------------------------------------------------------ snapshots

    private suspend fun maybeSnapshot() {
        val last = settings.get(DeviceSettings.SNAPSHOT_AT)?.toLongOrNull() ?: 0L
        if (now() - last < snapshotEveryMs) return
        snapshotNow()
    }

    /** Writes a full backup into snapshots/ (the safety net that makes everything else recoverable). */
    suspend fun snapshotNow() {
        val stamp = now()
        folder.write(SyncFolder.SNAPSHOTS, "$deviceId-$stamp.json", BackupService(db, now).export())
        settings.put(DeviceSettings.SNAPSHOT_AT, stamp.toString())
        // Only this device's own snapshots are ever deleted.
        val mine = folder.list(SyncFolder.SNAPSHOTS)
            .filter { it.startsWith("$deviceId-") }
            .sortedBy { it.substringAfter("$deviceId-").substringBefore('.').toLongOrNull() ?: 0L }
        mine.dropLast(snapshotsToKeep).forEach { folder.delete(SyncFolder.SNAPSHOTS, it) }
    }
}
