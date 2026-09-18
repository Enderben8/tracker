import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import revision.core.DatabaseFactory
import revision.core.seed.Seeder
import revision.core.systemNow

fun main() {
    val db = DatabaseFactory.open()
    Seeder.seedIfEmpty(db, systemNow)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Revision Tracker",
            state = rememberWindowState(size = DpSize(1000.dp, 800.dp)),
        ) {
            App(db, DesktopFileAccess())
        }
    }
}
