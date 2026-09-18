import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import revision.core.db.RevisionDatabase

@Composable
fun App(db: RevisionDatabase) {
    val subjects = db.subjectQueries.countAll().executeAsOne()
    val topics = db.topicQueries.countAll().executeAsOne()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Revision Tracker — Phase 1: database has $subjects subjects and $topics topics")
            }
        }
    }
}
