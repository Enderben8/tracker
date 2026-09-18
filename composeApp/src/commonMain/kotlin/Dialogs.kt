package revision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import revision.core.db.Subject
import revision.core.localDate
import revision.core.startOfDayMillis

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    multiline: Boolean = false,
    hint: String? = null,
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = !multiline,
                    modifier = if (multiline) Modifier.fillMaxWidth().height(200.dp) else Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onConfirm(text); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val hexColour = Regex("^#[0-9A-Fa-f]{6}$")

/** Edit a subject's name, colour, exam board and exam date. */
@Composable
fun SubjectEditDialog(state: AppState, subject: Subject, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(subject.name) }
    var colour by remember { mutableStateOf(subject.colour) }
    var board by remember { mutableStateOf(subject.exam_board ?: "") }
    var date by remember { mutableStateOf(subject.exam_date?.let { localDate(it).toString() } ?: "") }

    val parsedDate = if (date.isBlank()) null else runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val dateOk = date.isBlank() || parsedDate != null
    val colourOk = hexColour.matches(colour.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${subject.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(colour, { colour = it }, label = { Text("Colour (#RRGGBB)") }, singleLine = true, isError = !colourOk, modifier = Modifier.weight(1f))
                    Box(Modifier.size(28.dp).background(if (colourOk) parseColour(colour.trim()) else MaterialTheme.colorScheme.outline, CircleShape))
                }
                OutlinedTextField(board, { board = it }, label = { Text("Exam board (e.g. AQA)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    date, { date = it },
                    label = { Text("Exam date (YYYY-MM-DD, blank if unknown)") },
                    singleLine = true, isError = !dateOk, modifier = Modifier.fillMaxWidth(),
                )
                Text("Exam dates make the nearer subject rank higher in \"Revise next\".", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && dateOk && colourOk,
                onClick = {
                    val millis = parsedDate?.let { startOfDayMillis(it, TimeZone.currentSystemDefault()) }
                    state.subjectEditor.edit(subject.id, name, colour.trim(), board, millis)
                    state.dataChanged()
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
