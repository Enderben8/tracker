package revision.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.withTimeoutOrNull
import revision.core.DatabaseFactory
import revision.core.seed.Seeder
import revision.core.systemNow
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.swing.JOptionPane

fun main() {
    val dbFile = DatabaseFactory.defaultDatabaseFile()
    // Two copies would both run timers against the same database and race each other's sync.
    val instanceLock = lockSingleInstance(dbFile.parentFile)
    if (instanceLock == null) {
        JOptionPane.showMessageDialog(
            null,
            "Revision Tracker is already open. Look for it on the taskbar.",
            "Revision Tracker",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }

    val db = DatabaseFactory.open(dbFile)
    Seeder.seedIfEmpty(db, systemNow)

    application {
        val sync = rememberSyncManager(db, DesktopSyncPlatform())
        var closing by remember { mutableStateOf(false) }

        // On close, hide the window at once but send any last changes before exiting,
        // so a session finished just before closing reaches the other device now, not next launch.
        if (closing) {
            LaunchedEffect(Unit) {
                withTimeoutOrNull(15_000) { sync.flush() }
                exitApplication()
            }
        }

        Window(
            onCloseRequest = { closing = true },
            visible = !closing,
            title = "Revision Tracker",
            icon = LogoPainter(),
            state = rememberWindowState(size = DpSize(1000.dp, 800.dp)),
        ) {
            App(db, DesktopFileAccess(), sync)
        }
    }
    // Also keeps the lock reachable until here, so it can't be garbage-collected (and released) early.
    instanceLock.release()
}

/** Holds an OS file lock for as long as the app runs; null if another copy already holds it. */
private fun lockSingleInstance(dir: File): FileLock? =
    runCatching { RandomAccessFile(File(dir, "app.lock"), "rw").channel.tryLock() }.getOrNull()
