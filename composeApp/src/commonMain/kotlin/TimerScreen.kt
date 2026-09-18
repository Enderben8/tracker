package revision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import revision.core.formatClock
import revision.core.timer.ActiveSession
import revision.core.timer.TopicRating

@Composable
fun TimerScreen(state: AppState) {
    val active = state.active
    if (active == null) SetupView(state) else RunningView(state, active)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupView(state: AppState) {
    val subjects = remember { state.subjects.getAll() }
    var subjectId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start a session", style = MaterialTheme.typography.headlineSmall)
        Text("Pick a subject, then one or more topics from it.", style = MaterialTheme.typography.bodyMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subjects.forEach { s ->
                FilterChip(
                    selected = subjectId == s.id,
                    onClick = { subjectId = s.id; selected = emptySet() },
                    label = { Text(s.name) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(parseColour(s.colour), CircleShape)) },
                )
            }
        }

        val id = subjectId
        if (id != null) {
            val topics = remember(id) { state.topics.getBySubject(id) }
            TopicPicker(
                topics = topics,
                selected = selected,
                onToggle = { t -> selected = if (t in selected) selected - t else selected + t },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    // Keep the order the user sees in the tree rather than the order they ticked.
                    val ordered = topics.filter { it.id in selected }.map { it.id }
                    state.start(id, ordered)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start  (${selected.size} topic${if (selected.size == 1) "" else "s"})") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningView(state: AppState, active: ActiveSession) {
    var showAdd by remember { mutableStateOf(false) }
    var showStop by remember { mutableStateOf(false) }
    val subject = remember(active.subjectId) { state.subjects.get(active.subjectId) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).background(parseColour(subject?.colour ?: "#888888"), CircleShape))
            Text(subject?.name ?: "", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            formatClock(active.totalMs),
            fontSize = if (LocalCompact.current) 64.sp else 88.sp,
            fontWeight = FontWeight.Light,
            color = if (active.isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        Text(if (active.isPaused) "Paused" else "Running", style = MaterialTheme.typography.labelLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (active.isPaused) Button(onClick = { state.resume() }) { Text("Resume") }
            else OutlinedButton(onClick = { state.pause() }) { Text("Pause") }
            Button(onClick = { showStop = true }) { Text("Stop") }
        }

        Text("Tap a topic to switch to it — the clock keeps running.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            active.topics.forEach { t ->
                FilterChip(
                    selected = t.sessionTopicId == active.runningSessionTopicId,
                    onClick = { state.switchTo(t.sessionTopicId) },
                    label = { Text("${t.title} · ${formatClock(t.totalMs)}") },
                )
            }
            OutlinedButton(onClick = { showAdd = true }) { Text("+ Add topic") }
        }
    }

    if (showAdd) AddTopicDialog(state, active, onDismiss = { showAdd = false })
    if (showStop) StopDialog(state, active, onDismiss = { showStop = false })
}

@Composable
private fun AddTopicDialog(state: AppState, active: ActiveSession, onDismiss: () -> Unit) {
    val topics = remember(active.subjectId) { state.topics.getBySubject(active.subjectId) }
    val already = active.topics.map { it.topicId }.toSet()
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add topics to this session") },
        text = {
            TopicPicker(
                topics = topics,
                selected = selected,
                onToggle = { t -> selected = if (t in selected) selected - t else selected + t },
                excludeIds = already,
                modifier = Modifier.height(420.dp).fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = selected.isNotEmpty(),
                onClick = { state.addTopics(topics.filter { it.id in selected }.map { it.id }); onDismiss() },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopDialog(state: AppState, active: ActiveSession, onDismiss: () -> Unit) {
    val ratings = remember { mutableStateMapOf<String, Int>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    var sessionNote by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How did it go?") },
        text = {
            Column(Modifier.height(420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rate each topic 1–5 (1 = struggled, 5 = confident). Ratings are optional — skipping one leaves its schedule untouched.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(active.topics.size) { i ->
                        val t = active.topics[i]
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${t.title} — ${formatClock(t.totalMs)}", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (1..5).forEach { r ->
                                    FilterChip(
                                        selected = ratings[t.sessionTopicId] == r,
                                        onClick = {
                                            if (ratings[t.sessionTopicId] == r) ratings.remove(t.sessionTopicId)
                                            else ratings[t.sessionTopicId] = r
                                        },
                                        label = { Text("$r") },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = notes[t.sessionTopicId] ?: "",
                                onValueChange = { notes[t.sessionTopicId] = it },
                                label = { Text("Note (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = sessionNote,
                    onValueChange = { sessionNote = it },
                    label = { Text("Session note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = active.topics.associate {
                    it.sessionTopicId to TopicRating(ratings[it.sessionTopicId], notes[it.sessionTopicId]?.ifBlank { null })
                }
                state.stop(result, sessionNote.ifBlank { null })
                onDismiss()
            }) { Text("Save session") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDiscard = true }) { Text("Discard") }
                TextButton(onClick = onDismiss) { Text("Keep going") }
            }
        },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this session?") },
            text = { Text("The ${formatClock(active.totalMs)} you recorded will be deleted.") },
            confirmButton = { Button(onClick = { state.discardActive(); onDismiss() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}
