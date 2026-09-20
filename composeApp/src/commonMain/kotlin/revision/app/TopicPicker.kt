package revision.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import revision.core.db.Topic
import revision.core.formatAgo
import revision.core.scheduling.Suggestion
import revision.core.systemNow

private data class PickerRow(val topic: Topic, val depth: Int, val isLeaf: Boolean)

/**
 * Picks topics from one subject. When [suggestions] are given, the DEFAULT view is "Revise next" -
 * this subject's topics ranked by what needs revising most, each with the reason - with a switch to
 * "All topics" (a searchable tree) for anything else. Only leaf topics can be ticked: a parent such
 * as "Section Three - Networks" is a grouping, not something you revise. Ticks are kept when you
 * switch between the two views.
 */
@Composable
fun TopicPicker(
    topics: List<Topic>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    excludeIds: Set<String> = emptySet(),
    suggestions: List<Suggestion> = emptyList(),
) {
    val visibleSuggestions = suggestions.filter { it.topicId !in excludeIds }
    var showAll by remember { mutableStateOf(visibleSuggestions.isEmpty()) }

    Column(modifier) {
        if (visibleSuggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Revise next") })
                FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("All topics") })
            }
        }
        if (showAll || visibleSuggestions.isEmpty()) AllTopics(topics, selected, onToggle, excludeIds)
        else SuggestedTopics(visibleSuggestions, selected, onToggle)
    }
}

@Composable
private fun SuggestedTopics(suggestions: List<Suggestion>, selected: Set<String>, onToggle: (String) -> Unit) {
    val now = systemNow()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(suggestions, key = { it.topicId }) { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(s.topicId) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Checkbox(checked = s.topicId in selected, onCheckedChange = { onToggle(s.topicId) })
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                    // Only the nearest heading, on one line: the full path is too long for a phone.
                    if (s.context.isNotBlank()) Text(s.context.substringAfterLast(" › "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        "${s.reason}  ·  last: ${formatAgo(s.lastRevisedAt, now)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllTopics(topics: List<Topic>, selected: Set<String>, onToggle: (String) -> Unit, excludeIds: Set<String>) {
    var filter by remember { mutableStateOf("") }
    val expanded: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }

    val byParent = remember(topics) { topics.groupBy { it.parent_id } }
    val rows = remember(topics, filter, expanded.toMap(), excludeIds) {
        buildRows(byParent, filter.trim(), expanded, excludeIds)
    }

    Column {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search topics") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { it.topic.id }) { row ->
                val t = row.topic
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (row.isLeaf) onToggle(t.id) else expanded[t.id] = !(expanded[t.id] ?: false)
                        }
                        .padding(start = (row.depth * 20).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (row.isLeaf) {
                        Checkbox(checked = t.id in selected, onCheckedChange = { onToggle(t.id) })
                        Text(topicLabel(t), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val open = filter.isNotBlank() || (expanded[t.id] ?: false)
                        Text(if (open) "▾" else "▸", Modifier.padding(start = 12.dp, end = 4.dp))
                        Text(t.title, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

private fun topicLabel(t: Topic): String = if (t.code != null) "${t.code}  ${t.title}" else t.title

private fun buildRows(
    byParent: Map<String?, List<Topic>>,
    filter: String,
    expanded: Map<String, Boolean>,
    excludeIds: Set<String>,
): List<PickerRow> {
    val out = mutableListOf<PickerRow>()
    fun isLeaf(t: Topic) = byParent[t.id].isNullOrEmpty()

    // Does this topic, or anything under it, match the search text?
    fun matches(t: Topic): Boolean =
        t.title.contains(filter, ignoreCase = true) || (byParent[t.id] ?: emptyList()).any { matches(it) }

    fun walk(parentId: String?, depth: Int) {
        for (t in byParent[parentId] ?: emptyList()) {
            if (t.id in excludeIds) continue
            if (filter.isNotEmpty() && !matches(t)) continue
            val leaf = isLeaf(t)
            out += PickerRow(t, depth, leaf)
            if (!leaf && (filter.isNotEmpty() || expanded[t.id] == true)) walk(t.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}
