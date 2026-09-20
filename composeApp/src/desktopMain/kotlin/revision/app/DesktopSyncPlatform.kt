package revision.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import revision.core.DatabaseFactory
import revision.core.sync.SyncFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser

/** A folder on disk - normally inside Google Drive for desktop, which keeps it in sync. */
class FileSyncFolder(private val root: File) : SyncFolder {
    private fun dir(name: String) = File(root, name)

    // File work runs off the UI thread: with a cloud drive that streams files, a first read can block for seconds.
    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        dir(dir).listFiles { f -> f.isFile && !f.name.startsWith(".") }?.map { it.name }.orEmpty()
    }

    override suspend fun read(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        File(dir(dir), name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    override suspend fun write(dir: String, name: String, text: String): Unit = withContext(Dispatchers.IO) {
        val folder = dir(dir).also { it.mkdirs() }
        // Write beside it and move into place, so the cloud drive never uploads a half-written file.
        val temp = File(folder, ".$name.tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun delete(dir: String, name: String) {
        withContext(Dispatchers.IO) { File(dir(dir), name).delete() }
    }
}

class DesktopSyncPlatform : SyncPlatform {
    /** Google Drive for desktop shows up as a "My Drive" folder, usually on its own drive letter (G:). */
    private fun googleDrive(): File? {
        val home = File(System.getProperty("user.home"))
        val candidates = File.listRoots().map { File(it, "My Drive") } +
            listOf(File(home, "My Drive"), File(home, "Google Drive/My Drive"), File(home, "Google Drive"))
        return candidates.firstOrNull { it.isDirectory }
    }

    override fun suggestedLocation(): String? = googleDrive()?.let { File(it, "RevisionTracker").path }

    override suspend fun chooseFolder(): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser(googleDrive()?.path ?: System.getProperty("user.home")).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose the shared sync folder (inside Google Drive)"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
    }

    override fun open(location: String): SyncFolder = FileSyncFolder(File(location))

    override fun describe(location: String) = location

    /** The database must never live in (or be swept up by) the shared folder: OneDrive would corrupt it. */
    override fun problemWith(location: String): String? {
        val db = DatabaseFactory.defaultDatabaseFile().parentFile.canonicalFile
        val chosen = File(location).canonicalFile
        return if (chosen == db || db.path.startsWith(chosen.path + File.separator) || chosen.path.startsWith(db.path + File.separator))
            "That folder contains the app's own database. Pick a different folder - the database must never be shared."
        else null
    }
}
