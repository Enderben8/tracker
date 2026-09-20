package revision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import revision.core.db.Topic
import revision.core.formatAgo
import revision.core.formatDuration
import revision.core.stats.Roll
import revision.core.stats.rollUp

private data class TreeRow(val topic: Topic, val depth: Int, val hasChildren: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManageScreen(state: AppState) {
    val version = state.historyVersion
    val subjects = remember(version) { state.subjects.getAll() }
    var subjectId by state::manageSubjectId
    var showArchived by remember { mutableStateOf(false) }
    var editSubject by remember { mutableStateOf(false) }
    var addSubject by remember { mutableStateOf(false) }
    var archiveSubject by remember { mutableStateOf(false) }

    val selectedSubject = subjects.firstOrNull { it.id == subjectId }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subjects & topics", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { addSubject = true }) { Text("+ Subject") }
        }
        Text(
            "Several starting lists are educated guesses (Maths, French, English Literature sub-topics, and any Geography chapter your course skips). Rename, reorder or archive anything.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { s ->
                FilterChip(
                    selected = subjectId == s.id,
                    onClick = { subjectId = s.id },
                    label = { Text(s.name) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(parseColour(s.colour), CircleShape)) },
                )
            }
        }

        if (selectedSubject != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editSubject = true }) { Text("Edit subject / exam date") }
                TextButton(onClick = { state.subjectEditor.moveUp(selectedSubject.id); state.dataChanged() }) { Text("Move earlier") }
                TextButton(onClick = { state.subjectEditor.moveDown(selectedSubject.id); state.dataChanged() }) { Text("Move later") }
                TextButton(onClick = { archiveSubject = true }) { Text("Archive subject") }
            }
            HorizontalDivider()
            TopicTree(state, selectedSubject.id, showArchived) { showArchived = it }
        } else {
            val archivedSubjects = remember(version) { state.subjectEditor.archived() }
            Text("Choose a subject above to edit its topics.", style = MaterialTheme.typography.bodyMedium)
            if (archivedSubjects.isNotEmpty()) {
                Text("Archived subjects", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                archivedSubjects.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f))
                        TextButton(onClick = { state.subjects.restore(s.id); state.dataChanged() }) { Text("Restore") }
                    }
                }
            }
        }
    }

    if (editSubject && selectedSubject != null) SubjectEditDialog(state, selectedSubject) { editSubject = false }
    if (addSubject) TextPromptDialog(
        title = "New subject", label = "Name", confirmLabel = "Add",
        onConfirm = { name ->
            val id = state.subjects.add(name.trim(), "#7E57C2")
            subjectId = id
            state.dataChanged()
        },
        onDismiss = { addSubject = false },
    )
    if (archiveSubject && selectedSubject != null) ConfirmDialog(
        title = "Archive ${selectedSubject.name}?",
        text = "It disappears from suggestions and the timer. Your history is kept, and you can restore it from this screen.",
        confirmLabel = "Archive",
        onConfirm = { state.subjects.archive(selectedSubject.id); subjectId = null; state.dataChanged() },
        onDismiss = { archiveSubject = false },
    )
}

@Composable
private fun TopicTree(state: AppState, subjectId: String, showArchived: Boolean, setShowArchived: (Boolean) -> Unit) {
    val version = state.historyVersion
    val topics = remember(subjectId, version) { state.topics.getBySubject(subjectId) }
    val rolls: Map<String, Roll> = remember(subjectId, version) {
        val totals = state.sessions.let { state.db.sessionQueries.totalsByTopic(state.tick).executeAsList() }
            .associate { it.topic_id to (it.total_ms ?: 0L) }
        val last = state.db.topicStateQueries.selectAll().executeAsList()
            .mapNotNull { s -> s.last_revised_at?.let { s.topic_id to it } }.toMap()
        rollUp(topics, totals, last)
    }
    val archived = remember(subjectId, version) { state.topicEditor.archived().filter { it.subject_id == subjectId } }
    val expanded = remember(subjectId) { mutableStateMapOf<String, Boolean>() }
    val selected = remember(subjectId) { mutableStateMapOf<String, Boolean>() }
    var renaming by remember { mutableStateOf<Topic?>(null) }
    var adding by remember { mutableStateOf<Topic?>(null) }
    var addingTop by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf(false) }

    val byParent = remember(topics) { topics.groupBy { it.parent_id } }
    val rows = remember(topics, expanded.toMap()) {
        val out = mutableListOf<TreeRow>()
        fun walk(parent: String?, depth: Int) {
            for (t in byParent[parent].orEmpty()) {
                val kids = byParent[t.id].orEmpty().isNotEmpty()
                out += TreeRow(t, depth, kids)
                if (kids && expanded[t.id] == true) walk(t.id, depth + 1)
            }
        }
        walk(null, 0)
        out
    }
    val chosen = selected.filterValues { it }.keys

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { addingTop = true }) { Text("+ Add topics") }
            if (chosen.isNotEmpty()) {
                Button(onClick = { confirmBulk = true }) { Text("Archive ${chosen.size} selected") }
                TextButton(onClick = { selected.clear() }) { Text("Clear selection") }
            }
            Box(Modifier.weight(1f))
            TextButton(onClick = { setShowArchived(!showArchived) }) { Text(if (showArchived) "Hide archived" else "Archived (${archived.size})") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(rows, key = { it.topic.id }) { row ->
                val t = row.topic
                val roll = rolls[t.id]
                Row(
                    Modifier.fillMaxWidth().padding(start = (row.depth * 22).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selected[t.id] == true, onCheckedChange = { selected[t.id] = it })
                    Text(
                        if (!row.hasChildren) "   " else if (expanded[t.id] == true) "▾ " else "▸ ",
                        Modifier.clickable(enabled = row.hasChildren) { expanded[t.id] = !(expanded[t.id] ?: false) },
                    )
                    Column(Modifier.weight(1f).clickable(enabled = row.hasChildren) { expanded[t.id] = !(expanded[t.id] ?: false) }) {
                        Text(
                            (t.code?.let { "$it  " } ?: "") + t.title,
                            style = if (row.hasChildren) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        )
                        if (roll != null) {
                            val coverage = if (row.hasChildren) "  ·  ${roll.leafCount - roll.neverRevisedLeaves}/${roll.leafCount} revised" else ""
                            Text(
                                "${formatDuration(roll.totalMs)}  ·  last: ${formatAgo(roll.lastRevisedAt, state.tick)}$coverage",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    TopicActions(
                        onUp = { state.topicEditor.moveUp(t.id); state.dataChanged() },
                        onDown = { state.topicEditor.moveDown(t.id); state.dataChanged() },
                        onRename = { renaming = t },
                        onAddSub = { adding = t },
                        onArchive = { state.topicEditor.archiveMany(listOf(t.id)); state.dataChanged() },
                    )
                }
            }
        }
        if (showArchived) {
            HorizontalDivider()
            if (archived.isEmpty()) Text("Nothing archived in this subject.", style = MaterialTheme.typography.bodySmall)
            archived.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { state.topicEditor.restore(t.id); state.dataChanged() }) { Text("Restore") }
                }
            }
        }
    }

    renaming?.let { t ->
        TextPromptDialog("Rename topic", "Title", initial = t.title, onConfirm = { state.topicEditor.rename(t.id, it); state.dataChanged() }, onDismiss = { renaming = null })
    }
    adding?.let { parent ->
        TextPromptDialog(
            title = "Add topics under \"${parent.title}\"", label = "One topic per line", multiline = true, confirmLabel = "Add",
            onConfirm = { state.topicEditor.addMany(subjectId, parent.id, it.lines()); expanded[parent.id] = true; state.dataChanged() },
            onDismiss = { adding = null },
        )
    }
    if (addingTop) TextPromptDialog(
        title = "Add topics", label = "One topic per line", multiline = true, confirmLabel = "Add",
        hint = "Adds to the top level of this subject. Use \"+ Sub\" on a topic to nest under it.",
        onConfirm = { state.topicEditor.addMany(subjectId, null, it.lines()); state.dataChanged() },
        onDismiss = { addingTop = false },
    )
    if (confirmBulk) ConfirmDialog(
        title = "Archive ${chosen.size} topics?",
        text = "They (and anything under them) stop appearing in suggestions and the timer. Session history is kept; you can restore them from \"Archived\".",
        confirmLabel = "Archive",
        onConfirm = { state.topicEditor.archiveMany(chosen); selected.clear(); state.dataChanged() },
        onDismiss = { confirmBulk = false },
    )
}

/** On a wide screen: buttons in the row. On a phone: a single "..." menu, so the title keeps its space. */
@Composable
private fun TopicActions(onUp: () -> Unit, onDown: () -> Unit, onRename: () -> Unit, onAddSub: () -> Unit, onArchive: () -> Unit) {
    if (LocalCompact.current) {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("\u22EF") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; onUp() })
                DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; onDown() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
                DropdownMenuItem(text = { Text("Add sub-topics") }, onClick = { open = false; onAddSub() })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { open = false; onArchive() })
            }
        }
    } else {
        TextButton(onClick = onUp) { Text("\u2191") }
        TextButton(onClick = onDown) { Text("\u2193") }
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = onAddSub) { Text("+ Sub") }
        TextButton(onClick = onArchive) { Text("Archive") }
    }
}
