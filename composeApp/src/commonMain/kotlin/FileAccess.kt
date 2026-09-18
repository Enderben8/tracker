package revision.app

/**
 * How the shared UI asks the platform to save or open a file. Desktop uses a file dialog;
 * Android (Phase 5) will use the system document picker.
 */
interface FileAccess {
    /** Saves [text]; returns the saved file's path, or null if the user cancelled. */
    suspend fun saveText(suggestedName: String, text: String): String?

    /** Lets the user pick a file and returns its text, or null if they cancelled. */
    suspend fun openText(): String?
}

object NoFileAccess : FileAccess {
    override suspend fun saveText(suggestedName: String, text: String): String? = null
    override suspend fun openText(): String? = null
}
