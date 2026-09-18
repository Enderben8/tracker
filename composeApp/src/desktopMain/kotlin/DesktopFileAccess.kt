import java.awt.FileDialog
import java.awt.Frame
import java.io.File

class DesktopFileAccess : FileAccess {
    private val startDir = File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory }?.absolutePath

    override fun saveText(suggestedName: String, text: String): String? {
        val dialog = FileDialog(null as Frame?, "Save backup", FileDialog.SAVE).apply {
            file = suggestedName
            directory = startDir
            isVisible = true
        }
        val name = dialog.file ?: return null
        val target = File(dialog.directory, name)
        target.writeText(text, Charsets.UTF_8)
        return target.absolutePath
    }

    override fun openText(): String? {
        val dialog = FileDialog(null as Frame?, "Choose a backup file", FileDialog.LOAD).apply {
            file = "*.json"
            directory = startDir
            isVisible = true
        }
        val name = dialog.file ?: return null
        return File(dialog.directory, name).readText(Charsets.UTF_8)
    }
}
