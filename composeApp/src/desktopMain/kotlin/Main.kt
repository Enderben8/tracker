import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import revision.core.DatabaseFactory
import revision.core.seed.Seeder
import revision.core.systemNow

fun main() {
    val db = DatabaseFactory.open()
    Seeder.seedIfEmpty(db, systemNow)

    application {
        Window(onCloseRequest = ::exitApplication, title = "Revision Tracker") {
            App(db)
        }
    }
}
