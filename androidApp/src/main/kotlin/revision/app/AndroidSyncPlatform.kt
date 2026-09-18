package revision.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import revision.core.sync.SyncFolder

/**
 * The shared folder reached through Android's Storage Access Framework: the user picks a folder once
 * (for example inside Google Drive) and Android remembers the permission. No sign-in code, no server.
 * Whether a given provider is dependable is exactly what "Test folder" in Settings finds out.
 */
class SafSyncFolder(private val context: Context, private val tree: Uri) : SyncFolder {
    // Google Drive lets several files (or folders) share a name, and its listings can lag behind
    // changes. So: remember the exact files we created, never create a second copy of a name we
    // already know, and if duplicates do exist, use the most recently changed one.
    private val knownDirs = mutableMapOf<String, DocumentFile>()
    private val knownFiles = mutableMapOf<String, Uri>()

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, tree) ?: error("The folder is no longer available.")

    private fun dir(name: String, create: Boolean): DocumentFile? {
        knownDirs[name]?.let { return it }
        val found = root().listFiles().firstOrNull { it.isDirectory && it.name == name }
        val dir = found ?: if (create) root().createDirectory(name) ?: error("Could not create the '$name' folder.") else null
        if (dir != null) knownDirs[name] = dir
        return dir
    }

    private fun named(dir: String, name: String): List<DocumentFile> =
        dir(dir, create = false)?.listFiles()?.filter { it.isFile && it.name == name }.orEmpty()

    private fun locate(dir: String, name: String): DocumentFile? {
        knownFiles["$dir/$name"]?.let { uri ->
            DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.let { return it }
            knownFiles.remove("$dir/$name")
        }
        return named(dir, name).maxByOrNull { it.lastModified() }?.also { knownFiles["$dir/$name"] = it.uri }
    }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        val names = dir(dir, create = false)?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name }.orEmpty()
        (names + knownFiles.filterKeys { it.startsWith("$dir/") }.keys.map { it.removePrefix("$dir/") }).distinct()
    }

    override suspend fun read(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        val f = locate(dir, name) ?: return@withContext null
        context.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    override suspend fun write(dir: String, name: String, text: String): Unit = withContext(Dispatchers.IO) {
        val folder = dir(dir, create = true)!!
        val target = locate(dir, name) ?: folder.createFile("application/json", name) ?: error("Could not create $name.")
        knownFiles["$dir/$name"] = target.uri
        // "wt" = write and truncate, so a shorter file never leaves old bytes at the end.
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write $name.")
    }

    override suspend fun delete(dir: String, name: String) {
        withContext(Dispatchers.IO) {
            val known = knownFiles.remove("$dir/$name")?.let { DocumentFile.fromSingleUri(context, it) }
            known?.delete()
            named(dir, name).forEach { it.delete() }
        }
    }
}

class AndroidSyncPlatform(private val activity: ComponentActivity) : SyncPlatform {
    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pending?.complete(it) }

    override fun suggestedLocation(): String? = null

    override suspend fun chooseFolder(): String? {
        val result = CompletableDeferred<Uri?>().also { pending = it }
        picker.launch(null)
        val uri = result.await() ?: return null
        // Keep access across restarts.
        activity.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        // Ask the provider for the folder's name once, here (off the UI thread), and store it alongside
        // the address so Settings can show a readable name without touching the provider again.
        val name = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(activity, uri)?.name }
        return uri.toString() + SEPARATOR + (name ?: "Google Drive folder")
    }

    override fun open(location: String): SyncFolder =
        SafSyncFolder(activity.applicationContext, Uri.parse(location.substringBefore(SEPARATOR)))

    override fun describe(location: String): String =
        location.substringAfter(SEPARATOR, "").ifBlank { "your chosen Google Drive folder" }

    private companion object { const val SEPARATOR = "" }
}
