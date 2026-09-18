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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = DatabaseFactory.open(this)
        Seeder.seedIfEmpty(db, systemNow)
        val files = AndroidFileAccess(this)
        val syncPlatform = AndroidSyncPlatform(this)

        setContent {
            val state = remember { AppState(db) }
            // Back returns to Today first; back on Today leaves the app as usual.
            BackHandler(enabled = state.screen != Screen.Today) { state.screen = Screen.Today }
            App(db, files, syncPlatform, state)
        }
    }
}
