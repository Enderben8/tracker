package revision.core.sync

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

    /** When this device wrote its starting topics; rows untouched since then need not be pushed. */
    const val SEEDED_AT = "seeded_at"

    fun isLocal(key: String) = key == "heartbeat" || key == DEVICE_ID || key == SEEDED_AT || key.startsWith("sync.")

    /** This device's id; created on first use. */
    fun deviceId(settings: SettingsRepository): String =
        settings.get(DEVICE_ID) ?: newId().also { settings.put(DEVICE_ID, it) }
}

data class CheckStep(val name: String, val ok: Boolean, val detail: String, val ms: Long)

/**
 * Exercises a folder the way sync will: list, create, read back, overwrite, delete. Used to find
 * out whether a storage provider (especially Drive on Android) is dependable enough for sync.
 */
object SyncFolderCheck {
    suspend fun run(folder: SyncFolder, now: Now): List<CheckStep> {
        val steps = mutableListOf<CheckStep>()
        val file = "check-${newId().take(8)}.jsonl"
        var storedName: String? = null

        suspend fun step(name: String, body: suspend () -> String) {
            val start = now()
            val result = try { true to body() } catch (e: Throwable) { false to (e.message ?: e::class.simpleName.orEmpty()) }
            steps += CheckStep(name, result.first, result.second, now() - start)
        }

        step("List the devices folder") { "${folder.list(SyncFolder.DEVICES).size} file(s) there" }
        step("Create a test file") {
            folder.write(SyncFolder.DEVICES, file, "one\n"); "created $file"
        }
        step("Find it in the listing") {
            val names = folder.list(SyncFolder.DEVICES)
            storedName = names.firstOrNull { it.substringBefore('.') == file.substringBefore('.') }
                ?: error("not listed (saw: ${names.joinToString().ifEmpty { "nothing" }})")
            if (storedName == file) "listed as $file" else "listed as $storedName (renamed by the provider - sync copes with this)"
        }
        step("Read it back") {
            val text = folder.read(SyncFolder.DEVICES, storedName ?: file)
            if (text?.trim() == "one") "content matches" else error("got ${text?.take(30)?.let { "\"$it\"" } ?: "nothing"}")
        }
        step("Overwrite it with longer content") {
            folder.write(SyncFolder.DEVICES, storedName ?: file, "one\ntwo\nthree\n")
            val text = folder.read(SyncFolder.DEVICES, storedName ?: file)
            if (text == "one\ntwo\nthree\n") "replaced correctly" else error("read back ${text?.length} chars: ${text?.take(30)}")
        }
        step("Overwrite it with SHORTER content") {
            folder.write(SyncFolder.DEVICES, storedName ?: file, "x\n")
            val text = folder.read(SyncFolder.DEVICES, storedName ?: file)
            if (text == "x\n") "truncated correctly" else error("old bytes left behind: ${text?.take(30)}")
        }
        step("Delete it") {
            folder.delete(SyncFolder.DEVICES, storedName ?: file)
            val left = folder.list(SyncFolder.DEVICES).any { it.substringBefore('.') == file.substringBefore('.') }
            if (left) error("still listed after delete") else "gone"
        }
        return steps
    }
}
