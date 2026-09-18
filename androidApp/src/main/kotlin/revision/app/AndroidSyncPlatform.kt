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
    private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, tree) ?: error("The folder is no longer available.")

    private fun dir(name: String, create: Boolean): DocumentFile? {
        val root = root()
        val existing = root.findFile(name)?.takeIf { it.isDirectory }
        return existing ?: if (create) root.createDirectory(name) ?: error("Could not create the '$name' folder.") else null
    }

    private fun file(dir: String, name: String): DocumentFile? = dir(dir, create = false)?.findFile(name)

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        dir(dir, create = false)?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name }.orEmpty()
    }

    override suspend fun read(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        val f = file(dir, name) ?: return@withContext null
        context.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    override suspend fun write(dir: String, name: String, text: String): Unit = withContext(Dispatchers.IO) {
        val folder = dir(dir, create = true)!!
        val target = folder.findFile(name) ?: folder.createFile("application/json", name) ?: error("Could not create $name.")
        // "wt" = write and truncate, so a shorter file never leaves old bytes at the end.
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write $name.")
    }

    override suspend fun delete(dir: String, name: String) {
        withContext(Dispatchers.IO) { file(dir, name)?.delete() }
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
        return uri.toString()
    }

    override fun open(location: String): SyncFolder = SafSyncFolder(activity.applicationContext, Uri.parse(location))

    override fun describe(location: String): String =
        Uri.decode(Uri.parse(location).lastPathSegment ?: location)
}
