package revision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import revision.core.data.SettingsRepository
import revision.core.db.RevisionDatabase
import revision.core.formatTimeOfDay
import revision.core.sync.CheckStep
import revision.core.sync.DeviceSettings
import revision.core.sync.SyncEngine
import revision.core.sync.SyncFolder
import revision.core.sync.SyncFolderCheck
import revision.core.systemNow

/** What the platform supplies for sync: where the shared folder is, and how to read/write it. */
interface SyncPlatform {
    /** A sensible default folder (desktop: inside Google Drive), or null. */
    fun suggestedLocation(): String?

    /** Lets the user pick a folder. Returns a value to store (a path or a content URI), or null if cancelled. */
    suspend fun chooseFolder(): String?

    /** Opens the folder saved earlier. */
    fun open(location: String): SyncFolder

    /** A readable name for a stored location. */
    fun describe(location: String): String

    /** A problem with using this location (null if fine), e.g. it is where the database itself lives. */
    fun problemWith(location: String): String? = null
}

object NoSyncPlatform : SyncPlatform {
    override fun suggestedLocation(): String? = null
    override suspend fun chooseFolder(): String? = null
    override fun open(location: String): SyncFolder = error("Sync is not available here.")
    override fun describe(location: String) = location
}

/** A [SyncManager] that lives as long as the calling composable (the window, or the whole app). */
@Composable
fun rememberSyncManager(db: RevisionDatabase, platform: SyncPlatform): SyncManager {
    val scope = rememberCoroutineScope()
    return remember { SyncManager(db, platform, scope) }
}

/**
 * Runs sync in the background and reports how it is going. A failure is never fatal and never
 * blocks the timer: if the folder is unreachable, the app just carries on with its local data.
 */
class SyncManager(
    private val db: RevisionDatabase,
    private val platform: SyncPlatform,
    private val scope: CoroutineScope,
) {
    private val settings = SettingsRepository(db, systemNow)
    private val lock = Mutex()
    private var pending: Job? = null

    var enabled by mutableStateOf(settings.get(DeviceSettings.ENABLED) == "1")
        private set
    var location by mutableStateOf(settings.get(DeviceSettings.FOLDER))
        private set
    var status by mutableStateOf(if (enabled) "Waiting to sync…" else "Sync is off")
        private set
    var busy by mutableStateOf(false)
        private set
    var lastCheck by mutableStateOf<List<CheckStep>?>(null)
        private set
    /** Bumped when a sync brought in changes, so screens reload. */
    var pulledVersion by mutableStateOf(0)
        private set

    val available: Boolean get() = platform !== NoSyncPlatform

    fun suggested(): String? = platform.suggestedLocation()
    fun describe(location: String) = platform.describe(location)

    /** Ask the user for a folder and use it. Returns an error message, or null. */
    suspend fun chooseAndUse(): String? {
        val picked = platform.chooseFolder() ?: return null
        return use(picked)
    }

    fun use(picked: String): String? {
        platform.problemWith(picked)?.let { return it }
        settings.put(DeviceSettings.FOLDER, picked)
        location = picked
        return null
    }

    fun turnOn() {
        if (location == null) return
        settings.put(DeviceSettings.ENABLED, "1")
        enabled = true
        status = "Waiting to sync…"
        syncNow()
    }

    fun turnOff() {
        settings.put(DeviceSettings.ENABLED, "0")
        enabled = false
        pending?.cancel()
        status = "Sync is off"
    }

    /** Called whenever local data changes: sync shortly after the last change, not on every keystroke. */
    fun changed() {
        if (!enabled) return
        pending?.cancel()
        pending = scope.launch { delay(30_000); syncNow().join() }
    }

    fun syncNow(): Job = scope.launch {
        val where = location
        if (!enabled || where == null) return@launch
        if (!lock.tryLock()) return@launch // one at a time
        busy = true
        try {
            val result = SyncEngine(db, systemNow, platform.open(where)).sync()
            val bits = mutableListOf<String>()
            if (result.applied > 0) { bits += "${result.applied} received"; pulledVersion++ }
            if (result.pushed > 0) bits += "${result.pushed} sent"
            if (result.waiting > 0) bits += "${result.waiting} waiting for related data"
            status = "Synced " + formatTimeOfDay(systemNow()) + if (bits.isEmpty()) " - up to date" else " - " + bits.joinToString(", ")
        } catch (e: Throwable) {
            status = "Couldn't sync (${e.message ?: e::class.simpleName}). Your data is safe on this device; it will retry."
        } finally {
            busy = false
            lock.unlock()
        }
    }

    /**
     * Sends anything still waiting for the 30-second debounce, e.g. just before the app closes.
     * Waits for a sync that is already running rather than skipping.
     */
    suspend fun flush() {
        if (!enabled) return
        pending?.cancel()
        while (busy) delay(100)
        syncNow().join()
    }

    /** Periodic pull so changes from the other device show up without any action. */
    suspend fun pollLoop() {
        while (true) {
            delay(120_000)
            if (enabled && !busy) syncNow().join()
        }
    }

    /** Tries the folder the way sync will use it, and keeps the step-by-step result. */
    fun checkFolder(): Job = scope.launch {
        val where = location
        if (where == null) { lastCheck = listOf(CheckStep("Choose a folder first", false, "", 0)); return@launch }
        busy = true
        lastCheck = try {
            SyncFolderCheck.run(platform.open(where), systemNow)
        } catch (e: Throwable) {
            listOf(CheckStep("Open the folder", false, e.message ?: e::class.simpleName.orEmpty(), 0))
        }
        busy = false
    }

    fun dismissCheck() { lastCheck = null }
}
