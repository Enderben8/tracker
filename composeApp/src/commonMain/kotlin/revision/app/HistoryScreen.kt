package revision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import revision.core.formatDuration
import revision.core.formatTimeOfDay
import revision.core.localDate
import revision.core.startOfDayMillis
import revision.core.timer.HistoryEntry
import revision.core.timer.HistoryTopic
import revision.core.timer.ManualTopic

@Composable
fun HistoryScreen(state: AppState) {
    val entries = remember(state.historyVersion) { state.history.load() }
    val grouped = remember(entries) { entries.groupBy { localDate(it.startedAt) } }
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var toEdit by remember { mutableStateOf<Pair<HistoryEntry, HistoryTopic>?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { state.screen = Screen.LogPast }) { Text(if (LocalCompact.current) "Log past" else "Log a past session") }
        }
        if (entries.isEmpty()) {
            Text("No sessions yet. Finish a timer session, or log one you did away from the PC.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (day, list) ->
                item(key = "day-$day") {
                    Text(
                        "$day  ·  ${formatDuration(list.sumOf { it.totalMs })}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(list, key = { it.sessionId }) { e ->
                    SessionCard(e, onEditTopic = { t -> toEdit = e to t }, onDelete = { toDelete = e })
                }
            }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this session?") },
            text = { Text("${e.subjectName}, ${formatDuration(e.totalMs)}. It stops counting towards your totals.") },
            confirmButton = { Button(onClick = { state.deleteHistory(e.sessionId); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }

    toEdit?.let { (e, t) ->
        var text by remember(t.sessionTopicId) { mutableStateOf((t.ms / 60_000).toString()) }
        AlertDialog(
            onDismissRequest = { toEdit = null },
            title = { Text("Fix duration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.title)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter(Char::isDigit) },
                        label = { Text("Minutes") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = text.toIntOrNull() != null,
                    onClick = {
                        state.history.setTopicMinutes(e.sessionId, t.sessionTopicId, text.toInt())
                        state.historyChanged(); toEdit = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { toEdit = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionCard(e: HistoryEntry, onEditTopic: (HistoryTopic) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(parseColour(e.subjectColour), CircleShape))
                Text(e.subjectName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${formatTimeOfDay(e.startedAt)}${if (e.isManual) " (logged)" else ""}  ·  ${formatDuration(e.totalMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            e.topics.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEditTopic(t) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(if (t.rating != null) "★ ${t.rating}" else "unrated", style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(t.ms), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!e.notes.isNullOrBlank()) Text(e.notes!!, style = MaterialTheme.typography.bodySmall)
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            Text("Tap a topic's time to correct it.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogPastScreen(state: AppState, initialSubjectId: String? = null, initialSelected: List<String> = emptyList()) {
    val subjects = remember { state.subjects.getAll() }
    var subjectId by remember { mutableStateOf(initialSubjectId) }
    var selected by remember { mutableStateOf(initialSelected) }
    val minutes = remember { mutableStateMapOf<String, String>() }
    val ratings = remember { mutableStateMapOf<String, Int>() }
    var dateText by remember { mutableStateOf(localDate(state.tick).toString()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val id = subjectId

    // The form scrolls; the error and the Save button stay pinned at the bottom so they are never
    // pushed off a small screen (which made it look as if saving did nothing).
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log a past session", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.screen = Screen.History }) { Text("Cancel") }
            }
            Text("1. Subject", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { s ->
                    FilterChip(
                        selected = subjectId == s.id,
                        onClick = { subjectId = s.id; selected = emptyList(); error = null },
                        label = { Text(s.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(parseColour(s.colour), CircleShape)) },
                    )
                }
            }
            OutlinedTextField(
                value = dateText, onValueChange = { dateText = it; error = null },
                label = { Text("Date (YYYY-MM-DD)") }, singleLine = true,
            )

            if (id != null) {
                val topics = remember(id) { state.topics.getBySubject(id) }
                val suggestions = remember(id, state.historyVersion) { state.today.suggestionsForSubject(id) }
                Text("2. Topics (tick one or more)", style = MaterialTheme.typography.titleSmall)
                TopicPicker(
                    topics = topics,
                    selected = selected.toSet(),
                    onToggle = { t -> selected = if (t in selected) selected - t else selected + t; error = null },
                    modifier = Modifier.height(260.dp).fillMaxWidth(),
                    suggestions = suggestions,
                )
                if (selected.isNotEmpty()) {
                    Text("3. Minutes for each topic (required), and a rating if you like", style = MaterialTheme.typography.titleSmall)
                    selected.forEach { tid ->
                        val title = topics.firstOrNull { it.id == tid }?.title ?: ""
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, style = MaterialTheme.typography.bodySmall)
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedTextField(
                                    value = minutes[tid] ?: "",
                                    onValueChange = { minutes[tid] = it.filter(Char::isDigit); error = null },
                                    label = { Text("min") }, singleLine = true,
                                    modifier = Modifier.width(88.dp),
                                )
                                (1..5).forEach { r ->
                                    FilterChip(
                                        selected = ratings[tid] == r,
                                        onClick = { if (ratings[tid] == r) ratings.remove(tid) else ratings[tid] = r },
                                        label = { Text("$r") },
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Choose a subject to see its topics.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                enabled = id != null && selected.isNotEmpty(),
                onClick = {
                    val subject = id ?: return@Button
                    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                    val entries = selected.map { ManualTopic(it, minutes[it]?.toIntOrNull() ?: 0, ratings[it]) }
                    when {
                        date == null -> error = "Date must look like 2026-09-18."
                        entries.any { it.minutes <= 0 } -> error = "Enter the minutes for every ticked topic."
                        else -> {
                            // Noon local time, so a logged session can't be pushed across midnight by its length.
                            val start = startOfDayMillis(date, TimeZone.currentSystemDefault()) + 12 * 3_600_000L
                            state.history.logManual(subject, start, entries, note.ifBlank { null })
                            state.historyChanged()
                            state.screen = Screen.History
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (selected.isEmpty()) "Save session (tick a topic first)" else "Save session") }
        }
    }
}
