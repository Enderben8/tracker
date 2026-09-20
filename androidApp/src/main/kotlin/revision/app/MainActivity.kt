package revision.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import revision.core.DatabaseFactory
import revision.core.seed.Seeder
import revision.core.systemNow

class MainActivity : ComponentActivity() {
    private var sync: SyncManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = DatabaseFactory.open(this)
        Seeder.seedIfEmpty(db, systemNow)
        val files = AndroidFileAccess(this)
        val syncPlatform = AndroidSyncPlatform(this)

        setContent {
            val state = remember { AppState(db) }
            val sync = rememberSyncManager(db, syncPlatform).also { this@MainActivity.sync = it }
            // Back returns to Today first; back on Today leaves the app as usual.
            BackHandler(enabled = state.screen != Screen.Today) { state.screen = Screen.Today }
            App(db, files, sync, state)
        }
    }

    // Coming back to the app picks up the other device's changes; leaving it sends ours
    // straight away rather than after the usual 30-second wait (Android may stop the app soon after).
    override fun onRestart() {
        super.onRestart()
        sync?.syncNow()
    }

    override fun onStop() {
        super.onStop()
        sync?.syncNow()
    }
}
