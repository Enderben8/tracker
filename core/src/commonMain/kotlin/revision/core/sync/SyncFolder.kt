package revision.core.sync

import kotlinx.coroutines.delay
import revision.core.Now
import revision.core.data.SettingsRepository
import revision.core.newId

/**
 * A folder shared between devices (a Google Drive or OneDrive folder). Two sub-folders are used: "devices" for the
 * per-device change logs and "snapshots" for full backups. Implementations must create a
 * sub-folder on first write. Nothing here may block the UI: sync always runs in the background.
 */
interface SyncFolder {
    /** File names inside [dir]; empty if the folder does not exist yet. */
    suspend fun list(dir: String): List<String>

    /** The whole text of a file, or null if it is not there. */
    suspend fun read(dir: String, name: String): String?

    /** Creates or completely replaces a file. */
    suspend fun write(dir: String, name: String, text: String)

    suspend fun delete(dir: String, name: String)

    companion object {
        const val DEVICES = "devices"
        const val SNAPSHOTS = "snapshots"
    }
}

/**
 * Settings that belong to this one device and must never be exported, pushed or pulled: the device
 * id itself, where the sync folder is, and the sync bookkeeping.
 */
object DeviceSettings {
    const val DEVICE_ID = "device_id"
    const val ENABLED = "sync.enabled"
    const val FOLDER = "sync.folder"
    const val SEQ = "sync.seq"
    const val PUSHED_AT = "sync.pushed_at"
    const val GENERATION = "sync.generation"
    const val SNAPSHOT_AT = "sync.snapshot_at"
    const val LAST_OK = "sync.last_ok"
    const val PUBLISHED_SEQ = "sync.published_seq"
    const val COMPACTED_SIZE = "sync.compacted_size"
    const val PUBLISHED_GEN = "sync.published_gen"

    /** When this device wrote its starting topics; rows untouched since then need not be pushed. */
    const val SEEDED_AT = "seeded_at"

    fun isLocal(key: String) = key == "heartbeat" || key == DEVICE_ID || key == SEEDED_AT || key.startsWith("sync.")

    /** This device's id; created on first use. */
    fun deviceId(settings: SettingsRepository): String =
        settings.get(DEVICE_ID) ?: newId().also { settings.put(DEVICE_ID, it) }
}

data class CheckStep(val name: String, val ok: Boolean, val detail: String, val ms: Long)

/**
 * Exercises a folder the way sync will use it: create, list, read back, overwrite (longer and shorter),
 * make sure no duplicate copies appear, delete. Cloud drives are often slow to show a change, so each
 * step waits (up to [patienceMs]) for the result and reports how long it took; only a change that
 * never shows up counts as a failure. Used to find out whether a provider (especially the Google Drive
 * app on Android) is dependable before sync is turned on.
 */
object SyncFolderCheck {
    suspend fun run(
        folder: SyncFolder,
        now: Now,
        patienceMs: Long = 20_000,
        everyMs: Long = 1_000,
    ): List<CheckStep> {
        val steps = mutableListOf<CheckStep>()
        val file = "check-${newId().take(8)}.jsonl"
        val base = file.substringBefore('.')
        var stored: String = file

        suspend fun step(name: String, body: suspend () -> String) {
            val start = now()
            val result = try { true to body() } catch (e: Throwable) { false to (e.message ?: e::class.simpleName.orEmpty()) }
            steps += CheckStep(name, result.first, result.second, now() - start)
        }

        /** Polls until [condition] holds. Returns how many polls it took, or null if it never did. */
        suspend fun waitFor(condition: suspend () -> Boolean): Int? {
            val attempts = (patienceMs / everyMs).toInt().coerceAtLeast(1)
            for (i in 0..attempts) {
                if (condition()) return i
                if (i < attempts) delay(everyMs)
            }
            return null
        }

        fun took(polls: Int) = if (polls == 0) "immediately" else "after ~${polls * everyMs / 1000}s"
        suspend fun namesLike() = folder.list(SyncFolder.DEVICES).filter { it.substringBefore('.') == base }

        step("List the devices folder") { "${folder.list(SyncFolder.DEVICES).size} file(s) there" }
        step("Create a test file") { folder.write(SyncFolder.DEVICES, file, "one\n"); "created $file" }
        step("Find it in the listing") {
            val polls = waitFor { namesLike().isNotEmpty() } ?: error("never appeared in the listing")
            stored = namesLike().first()
            (if (stored == file) "listed as $file" else "listed as $stored (renamed by the provider - sync copes with this)") + ", " + took(polls)
        }
        step("Read it back") {
            var last: String? = null
            val polls = waitFor { folder.read(SyncFolder.DEVICES, stored).also { last = it }?.trim() == "one" }
                ?: error("still reads ${last?.take(30)?.let { "\"$it\"" } ?: "nothing"}")
            "content matches, " + took(polls)
        }
        step("Overwrite it with longer content") {
            folder.write(SyncFolder.DEVICES, stored, "one\ntwo\nthree\n")
            var last: String? = null
            val polls = waitFor { folder.read(SyncFolder.DEVICES, stored).also { last = it } == "one\ntwo\nthree\n" }
                ?: error("never showed the new content; last read: ${last?.take(30)?.let { "\"$it\"" } ?: "nothing"}")
            "replaced correctly, " + took(polls)
        }
        step("Overwrite it with SHORTER content") {
            folder.write(SyncFolder.DEVICES, stored, "x\n")
            var last: String? = null
            val polls = waitFor { folder.read(SyncFolder.DEVICES, stored).also { last = it } == "x\n" }
                ?: error("old bytes left behind or write ignored; last read: ${last?.take(30)?.let { "\"$it\"" } ?: "nothing"}")
            "truncated correctly, " + took(polls)
        }
        step("Only one copy of the file exists") {
            val count = namesLike().size
            if (count == 1) "one copy" else error("$count copies with the same name - the provider made duplicates")
        }
        step("Delete it") {
            folder.delete(SyncFolder.DEVICES, stored)
            val polls = waitFor { namesLike().isEmpty() } ?: error("still listed ${namesLike().size}x after delete")
            "gone, " + took(polls)
        }
        return steps
    }
}
