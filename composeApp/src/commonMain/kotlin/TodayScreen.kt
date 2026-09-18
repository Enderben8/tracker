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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import revision.core.formatAgo
import revision.core.formatDuration
import revision.core.scheduling.DayTotal
import revision.core.scheduling.Suggestion

@Composable
fun TodayScreen(state: AppState) {
    // Recomputed whenever a session is saved, and once a minute so "days overdue" stays current.
    val key = state.historyVersion to (state.tick / 60_000)
    val suggestions = remember(key) { state.today.suggestions() }
    val summary = remember(key) { state.today.timeSummary() }
    val active = state.active

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Today", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${formatDuration(summary.todayMs)} today  ·  ${formatDuration(summary.last7Ms)} in the last 7 days",
            style = MaterialTheme.typography.titleMedium,
        )
        WeekChart(summary.days)

        Text("Revise next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (active != null) {
            Text(
                "A session is in progress. Tapping a topic from the same subject adds it to that session.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (suggestions.isEmpty()) {
            Text("Nothing to suggest yet — every subject is archived or its exam has passed.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions, key = { it.topicId }) { s ->
                SuggestionCard(state, s, enabled = active == null || active.subjectId == s.subjectId)
            }
        }
    }
}

@Composable
private fun SuggestionCard(state: AppState, s: Suggestion, enabled: Boolean) {
    val subject = remember(s.subjectId) { state.subjects.get(s.subjectId) }
    Card(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { state.startOrAdd(s.subjectId, s.topicId) },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(12.dp).background(parseColour(subject?.colour ?: "#888888"), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                Text("${s.subjectName}  ·  ${s.reason}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "last: ${formatAgo(s.lastRevisedAt, state.tick)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Minutes revised per day for the last 7 days. One series, so no legend: the heading names it.
 * Only today's bar carries a value label; every day's figure is also in the summary line above.
 */
@Composable
private fun WeekChart(days: List<DayTotal>) {
    val max = (days.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(1L)
    val barArea = 96.dp
    val bar = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(barArea + 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { i, d ->
                val isToday = i == days.lastIndex
                val h = if (d.ms == 0L) 0.dp else (barArea * (d.ms.toFloat() / max)).coerceAtLeast(3.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isToday && d.ms > 0) Text(formatDuration(d.ms), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .width(24.dp)
                            .height(h)
                            .alpha(if (isToday) 1f else 0.55f)
                            .background(bar, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            days.forEach { d ->
                Text(
                    d.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                    Modifier.width(36.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
