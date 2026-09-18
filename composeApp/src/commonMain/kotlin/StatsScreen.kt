import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import revision.core.formatAgo
import revision.core.formatDuration
import revision.core.stats.SubjectStats

@Composable
fun StatsScreen(state: AppState) {
    val key = state.historyVersion to (state.tick / 60_000)
    val subjects = remember(key) { state.stats.subjectStats() }
    val activity = remember(key) { state.stats.activity() }
    var openSubject by remember { mutableStateOf<String?>(null) }
    val maxMs = (subjects.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Stats", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "${formatDuration(subjects.sumOf { it.totalMs })} revised in total  ·  " +
                    "${activity.daysActive} day${if (activity.daysActive == 1) "" else "s"} revised  ·  " +
                    "current run ${activity.currentRun}  ·  longest run ${activity.longestRun}",
                style = MaterialTheme.typography.titleSmall,
            )
        }

        item { Text("Coverage — topics you have revised at least once", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(subjects, key = { "cov-" + it.subjectId }) { s -> CoverageRow(s) }

        item { Text("Time per subject", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        item { Text("Tap a subject to see its topics.", style = MaterialTheme.typography.bodySmall) }
        items(subjects.sortedByDescending { it.totalMs }, key = { "time-" + it.subjectId }) { s ->
            Column(Modifier.clickable { openSubject = if (openSubject == s.subjectId) null else s.subjectId }) {
                TimeBar(s.name, s.totalMs, maxMs, parseColour(s.colour))
                if (openSubject == s.subjectId) SubjectTopics(state, s)
            }
        }
    }
}

@Composable
private fun CoverageRow(s: SubjectStats) {
    val revised = s.leafCount - s.neverRevised
    val fraction = if (s.leafCount == 0) 0f else revised.toFloat() / s.leafCount
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("$revised of ${s.leafCount}", style = MaterialTheme.typography.titleSmall)
            }
            Bar(fraction, parseColour(s.colour))
            Text(
                if (s.neverRevised == 0) "Every topic revised at least once" else "${s.neverRevised} not revised yet",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun TimeBar(label: String, ms: Long, maxMs: Long, colour: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(formatDuration(ms), style = MaterialTheme.typography.bodyMedium)
        }
        Bar(ms.toFloat() / maxMs, colour)
    }
}

/** A thin bar on a faint track; the fill is the subject's own colour, the text stays in text colours. */
@Composable
private fun Bar(fraction: Float, colour: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).background(colour, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun SubjectTopics(state: AppState, s: SubjectStats) {
    val topics = remember(s.subjectId, state.historyVersion) { state.stats.topicStats(s.subjectId) }
    val max = (topics.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        topics.take(20).forEach { t ->
            Column {
                Row {
                    Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (t.totalMs == 0L) "never" else "${formatDuration(t.totalMs)} · ${formatAgo(t.lastRevisedAt, state.tick)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Bar(t.totalMs.toFloat() / max, parseColour(s.colour))
            }
        }
        if (topics.size > 20) Text("…and ${topics.size - 20} more", style = MaterialTheme.typography.labelSmall)
    }
}
