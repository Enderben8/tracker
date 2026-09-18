package revision.app

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Save/open through Android's system document picker, so the app needs no storage permission
 * and the user can pick any place, including a OneDrive folder. Must be created in onCreate,
 * before the activity starts, because that is when result launchers can be registered.
 */
class AndroidFileAccess(private val activity: ComponentActivity) : FileAccess {
    private var pendingSave: CompletableDeferred<Uri?>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null

    private val createLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { pendingSave?.complete(it) }

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { pendingOpen?.complete(it) }

    override suspend fun saveText(suggestedName: String, text: String): String? {
        val result = CompletableDeferred<Uri?>().also { pendingSave = it }
        createLauncher.launch(suggestedName)
        val uri = result.await() ?: return null
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not write to that location.")
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    override suspend fun openText(): String? {
        val result = CompletableDeferred<Uri?>().also { pendingOpen = it }
        openLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        val uri = result.await() ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: error("Could not read that file.")
        }
    }
}
