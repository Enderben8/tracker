import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import revision.core.db.Subject
import revision.core.localDate
import revision.core.scheduling.DAY_MS
import revision.core.scheduling.SchedulerConfig
import revision.core.seed.Seeder
import revision.core.systemNow

@Composable
fun SettingsScreen(state: AppState, files: FileAccess) {
    val version = state.historyVersion
    val subjects = remember(version) { state.subjects.getAll() }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Subject?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }

        item { Text("Exam dates & boards", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Add each exam date once you have your timetable. Until then every subject gets the same neutral weighting, so nothing breaks.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(subjects, key = { "s-" + it.id }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(examSummary(s, state.tick), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editing = s }) { Text("Edit") }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Backup", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Export saves everything to a JSON file. Importing merges a file into what you have — the newer edit of each item wins, so nothing is thrown away. This is your only backup until sync exists.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        val name = "revision-backup-${localDate(systemNow())}.json"
                        val path = files.saveText(name, state.backup.export())
                        message = if (path == null) "Export cancelled." else "Saved to $path"
                    }.onFailure { message = "Export failed: ${it.message}" }
                }) { Text("Export to file…") }
                OutlinedButton(onClick = {
                    runCatching {
                        val text = files.openText()
                        if (text == null) message = "Import cancelled."
                        else { message = "Imported: " + state.backup.import(text); state.dataChanged() }
                    }.onFailure { message = it.message ?: "Import failed." }
                }) { Text("Import from file…") }
            }
        }
        message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "▾ Advanced: \"revise next\" weighting" else "▸ Advanced: \"revise next\" weighting")
            }
        }
        if (showAdvanced) item { AdvancedWeights(state) }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Reset", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmRestore = true }) { Text("Restore starting topics") }
                OutlinedButton(onClick = { confirmReset = true }) { Text("Erase everything…", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    editing?.let { SubjectEditDialog(state, it) { editing = null } }
    if (confirmRestore) ConfirmDialog(
        title = "Restore starting topics?",
        text = "Puts back any starting subject or topic you archived or removed. Anything you renamed, added or reordered is left alone, and no history is touched.",
        confirmLabel = "Restore",
        onConfirm = { val n = Seeder.restoreMissing(state.db, systemNow); state.dataChanged(); message = "Restored $n item${if (n == 1) "" else "s"}." },
        onDismiss = { confirmRestore = false },
    )
    if (confirmReset) ConfirmDialog(
        title = "Erase everything?",
        text = "This deletes ALL sessions, ratings, schedules and your own topics, then re-creates the starting topic lists. It cannot be undone — export a backup first.",
        confirmLabel = "Erase everything",
        onConfirm = { Seeder.resetAll(state.db, systemNow); state.dataChanged(); message = "Everything was erased and the starting topics restored." },
        onDismiss = { confirmReset = false },
    )
}

private fun examSummary(s: Subject, now: Long): String {
    val board = s.exam_board ?: "board not set"
    val date = s.exam_date ?: return "$board  ·  exam date not set"
    val days = (date - now) / DAY_MS.toDouble()
    val when_ = when {
        now >= date + DAY_MS -> "exam has passed (hidden from suggestions)"
        days < 1 -> "exam is today"
        else -> "in ${days.toLong()} days"
    }
    return "$board  ·  ${localDate(date)}  ·  $when_"
}

@Composable
private fun AdvancedWeights(state: AppState) {
    val start = remember { state.schedulerConfig.load() }
    var overdue by remember { mutableStateOf(start.overdueWeight.toString()) }
    var never by remember { mutableStateOf(start.neverRevisedWeight.toString()) }
    var stale by remember { mutableStateOf(start.stalenessWeight.toString()) }
    var exam by remember { mutableStateOf(start.examPressureWeight.toString()) }
    var just by remember { mutableStateOf(start.justRevisedPenalty.toString()) }
    var size by remember { mutableStateOf(start.queueSize.toString()) }
    var per by remember { mutableStateOf(start.maxPerSubject.toString()) }
    var note by remember { mutableStateOf<String?>(null) }

    @Composable fun field(label: String, value: String, set: (String) -> Unit) =
        OutlinedTextField(value, { set(it.filter { c -> c.isDigit() || c == '.' }) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Score = overdue + never revised + staleness + exam pressure − just revised. Bigger number = stronger pull. Changes apply to the next suggestions; defaults are sensible.",
            style = MaterialTheme.typography.bodySmall,
        )
        field("Overdue weight (default 100)", overdue) { overdue = it }
        field("Never revised weight (default 60)", never) { never = it }
        field("Staleness weight (default 40)", stale) { stale = it }
        field("Exam pressure weight (default 50)", exam) { exam = it }
        field("Just-revised penalty (default 30)", just) { just = it }
        field("Suggestions shown (default 10)", size) { size = it }
        field("Max per subject (default 3)", per) { per = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val d = SchedulerConfig()
                state.schedulerConfig.save(
                    d.copy(
                        overdueWeight = overdue.toDoubleOrNull() ?: d.overdueWeight,
                        neverRevisedWeight = never.toDoubleOrNull() ?: d.neverRevisedWeight,
                        stalenessWeight = stale.toDoubleOrNull() ?: d.stalenessWeight,
                        examPressureWeight = exam.toDoubleOrNull() ?: d.examPressureWeight,
                        justRevisedPenalty = just.toDoubleOrNull() ?: d.justRevisedPenalty,
                        queueSize = size.toIntOrNull() ?: d.queueSize,
                        maxPerSubject = per.toIntOrNull() ?: d.maxPerSubject,
                    ),
                )
                state.dataChanged(); note = "Saved."
            }) { Text("Save") }
            OutlinedButton(onClick = {
                val d = SchedulerConfig()
                state.schedulerConfig.resetToDefaults()
                overdue = d.overdueWeight.toString(); never = d.neverRevisedWeight.toString(); stale = d.stalenessWeight.toString()
                exam = d.examPressureWeight.toString(); just = d.justRevisedPenalty.toString()
                size = d.queueSize.toString(); per = d.maxPerSubject.toString()
                state.dataChanged(); note = "Back to defaults."
            }) { Text("Reset to defaults") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
