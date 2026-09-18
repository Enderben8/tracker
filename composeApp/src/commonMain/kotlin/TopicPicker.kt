import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
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

private data class PickerRow(val topic: Topic, val depth: Int, val isLeaf: Boolean)

/**
 * A searchable, expandable topic tree. Only leaf topics can be ticked — a parent such as
 * "Section Three — Networks" is a grouping, not something you revise.
 */
@Composable
fun TopicPicker(
    topics: List<Topic>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    excludeIds: Set<String> = emptySet(),
) {
    var filter by remember { mutableStateOf("") }
    val expanded: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }

    val byParent = remember(topics) { topics.groupBy { it.parent_id } }
    val rows = remember(topics, filter, expanded.toMap(), excludeIds) {
        buildRows(byParent, filter.trim(), expanded, excludeIds)
    }

    Column(modifier) {
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
