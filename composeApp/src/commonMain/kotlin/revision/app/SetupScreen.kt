package revision.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import revision.core.catalogue.Board
import revision.core.catalogue.Catalogue
import revision.core.catalogue.CatalogueInstaller
import revision.core.catalogue.CatalogueSubject
import revision.core.catalogue.SpecSubject
import revision.core.catalogue.SubjectChoice
import revision.core.catalogue.Tier
import revision.core.systemNow

/**
 * First run: choose the subjects to revise and, for each, the board that examines it.
 *
 * Nothing is written to the database until Finish, so backing out costs nothing.
 */
@Composable
fun SetupScreen(
    state: AppState,
    sync: SyncManager,
    /** Where to start, and what is already ticked. Defaults are the real first run; tests set them. */
    initialStep: SetupStep = SetupStep.Subjects,
    initialPicked: Map<String, Board?> = emptyMap(),
    onFinished: () -> Unit,
) {
    var step by remember { mutableStateOf(initialStep) }
    // key -> chosen board (null until the user picks); absent key = subject not taken.
    val picked = remember { mutableStateMapOf<String, Board?>().apply { putAll(initialPicked) } }
    val tiers = remember { mutableStateMapOf<String, Tier>() }
    val options = remember { mutableStateMapOf<String, Boolean>() }   // "ref|groupRef" -> keep
    val ownSubjects = remember { mutableStateListOf<String>() }
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val chosen = Catalogue.bySubject.filter { it.key in picked }
    val specs = chosen.mapNotNull { subject -> picked[subject.key]?.let { subject.forBoard(it) } }
    val needsOptions = specs.filter { it.groups.any { group -> group.choice != null } || it.tiered }
    // A subject whose board is "not listed" becomes an empty subject to fill in by hand.
    val byHand = chosen.filter { picked[it.key] == null }.map { it.name } + ownSubjects

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set up your subjects", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(
            progress = { (step.ordinal + 1) / SetupStep.entries.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )

        Box(Modifier.weight(1f)) {
            when (step) {
                SetupStep.Subjects -> SubjectStep(picked, ownSubjects, onAddOwn = { adding = true }, sync = sync)
                SetupStep.Boards -> BoardStep(chosen, picked)
                SetupStep.Options -> OptionStep(needsOptions, tiers, options)
                SetupStep.Review -> ReviewStep(specs, byHand, tiers, options)
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != SetupStep.Subjects) {
                OutlinedButton(onClick = { step = previous(step, needsOptions.isEmpty()) }) { Text("Back") }
            }
            Box(Modifier.weight(1f))
            if (step == SetupStep.Review) {
                Button(
                    enabled = !busy && (specs.isNotEmpty() || byHand.isNotEmpty()),
                    onClick = {
                        busy = true
                        install(state, specs, byHand, tiers, options)
                        onFinished()
                    },
                ) { Text("Finish") }
            } else {
                Button(
                    enabled = picked.isNotEmpty() || ownSubjects.isNotEmpty(),
                    onClick = { step = next(step, picked, needsOptions.isEmpty()) },
                ) { Text("Next") }
            }
        }
    }

    if (adding) TextPromptDialog(
        title = "Add your own subject",
        label = "Subject name",
        hint = "You can add its topics yourself on the Topics screen afterwards.",
        confirmLabel = "Add",
        onConfirm = { ownSubjects.add(it.trim()) },
        onDismiss = { adding = false },
    )
}

private fun next(step: SetupStep, picked: Map<String, Board?>, skipOptions: Boolean): SetupStep = when (step) {
    SetupStep.Subjects -> SetupStep.Boards
    SetupStep.Boards -> if (skipOptions) SetupStep.Review else SetupStep.Options
    else -> SetupStep.Review
}

private fun previous(step: SetupStep, skipOptions: Boolean): SetupStep = when (step) {
    SetupStep.Review -> if (skipOptions) SetupStep.Boards else SetupStep.Options
    SetupStep.Options -> SetupStep.Boards
    else -> SetupStep.Subjects
}

// ---------------------------------------------------------------- step 1: subjects

@Composable
private fun SubjectStep(
    picked: MutableMap<String, Board?>,
    ownSubjects: List<String>,
    onAddOwn: () -> Unit,
    sync: SyncManager,
) {
    val scope = rememberCoroutineScope()
    var syncMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "Tick the subjects you are studying. Topic lists come from the exam boards' own " +
                    "specifications, and you can rename, add or remove anything later.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(Catalogue.bySubject, key = { it.key }) { subject ->
            val taken = subject.key in picked
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (taken) picked.remove(subject.key) else picked[subject.key] = subject.specs.first().board
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = taken, onCheckedChange = null)
                Box(Modifier.padding(horizontal = 8.dp).size(10.dp).background(parseColour(subject.colour), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(subject.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        subject.specs.joinToString(", ") { it.board.label },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ownSubjects.forEach { Text("• $it (your own)", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = onAddOwn) { Text("+ My own subject") }
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider()
                Text("Already using this on another device?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Point this device at the same shared folder and your subjects, topics and history " +
                        "come across on their own — no need to pick anything here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sync.available) {
                    TextButton(onClick = {
                        scope.launch {
                            syncMessage = sync.chooseAndUse() ?: run {
                                sync.turnOn()
                                "Looking for your other device…"
                            }
                        }
                    }) { Text("Set up sync instead…") }
                }
                syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ---------------------------------------------------------------- step 2: boards

@Composable
private fun BoardStep(chosen: List<CatalogueSubject>, picked: MutableMap<String, Board?>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "Which board examines each one? Mixing boards is normal — pick each subject's own.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Board.entries.forEach { board ->
                    val applicable = chosen.count { it.forBoard(board) != null }
                    if (applicable > 0) OutlinedButton(onClick = {
                        chosen.forEach { subject -> subject.forBoard(board)?.let { picked[subject.key] = board } }
                    }) { Text("Set all to ${board.label}") }
                }
            }
        }
        items(chosen, key = { it.key }) { subject ->
            val board = picked[subject.key]
            val spec = board?.let { subject.forBoard(it) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subject.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        spec?.let { "${it.specCode} · ${it.topicCount} topics · checked ${it.checkedOn}" }
                            ?: "Your own list — add topics yourself later",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BoardPicker(subject, board) { picked[subject.key] = it }
            }
        }
    }
}

@Composable
private fun BoardPicker(subject: CatalogueSubject, chosen: Board?, onPick: (Board?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(chosen?.label ?: "My own list") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            subject.specs.forEach { spec ->
                DropdownMenuItem(
                    text = { Text("${spec.board.label} (${spec.specCode})") },
                    onClick = { onPick(spec.board); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Not listed — my own list") },
                onClick = { onPick(null); open = false },
            )
        }
    }
}

// ---------------------------------------------------------------- step 3: options

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionStep(
    specs: List<SpecSubject>,
    tiers: MutableMap<String, Tier>,
    options: MutableMap<String, Boolean>,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "A few subjects let schools choose. Pick what you actually study — anything you leave " +
                    "out can be added later.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        specs.forEach { spec ->
            item { Text(spec.label, style = MaterialTheme.typography.titleMedium) }

            if (spec.tiered) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tier.entries.forEach { tier ->
                        FilterChip(
                            selected = tiers[spec.ref] == tier,
                            onClick = { tiers[spec.ref] = tier },
                            label = { Text(tier.label) },
                        )
                    }
                }
            }

            spec.groups.filter { it.choice != null }.groupBy { it.choice!!.group }.forEach { (label, groups) ->
                item { Text(label, style = MaterialTheme.typography.bodySmall) }
                items(groups, key = { "${spec.ref}|${it.ref}" }) { group ->
                    val key = "${spec.ref}|${group.ref}"
                    Row(
                        Modifier.fillMaxWidth().clickable { options[key] = options[key] != true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = options[key] == true, onCheckedChange = null)
                        Text(
                            group.title,
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- step 4: review

@Composable
private fun ReviewStep(
    specs: List<SpecSubject>,
    byHand: List<String>,
    tiers: Map<String, Tier>,
    options: Map<String, Boolean>,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Ready to go", style = MaterialTheme.typography.titleMedium) }
        items(specs, key = { it.ref }) { spec ->
            val choice = choiceFor(spec, tiers, options)
            val count = CatalogueInstaller.groupsFor(choice).sumOf { CatalogueInstaller.topicsFor(choice, it).size }
            Column {
                Text("${spec.name} — ${spec.board.label} ${spec.specCode}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$count topics · from the ${spec.board.label} specification, checked ${spec.checkedOn}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(byHand) { name ->
            Column {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text("Your own subject — no topics yet", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                "These lists are a starting point. Rename, add, reorder or archive anything on the " +
                    "Topics screen, and add more subjects from Settings whenever you like.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Adding a subject after setup: the same picker, limited to subjects that are not installed yet.
 * Its optional units default to the first of each, which the Topics screen can then adjust.
 */
@Composable
fun AddSubjectDialog(state: AppState, onDismiss: () -> Unit) {
    val installed = remember(state.historyVersion) { state.subjects.getAll().map { it.id }.toSet() }
    val available = remember(installed) {
        Catalogue.bySubject.filter { subject ->
            subject.specs.any { CatalogueInstaller.subjectIdFor(it) !in installed }
        }
    }
    var chosen by remember { mutableStateOf<CatalogueSubject?>(null) }
    var board by remember { mutableStateOf<Board?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a subject") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available.isEmpty()) {
                    Text("Every subject in the catalogue is already here.")
                } else {
                    Text(
                        "Topic lists come from the board's published specification.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(available, key = { it.key }) { subject ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    chosen = subject
                                    board = subject.specs.first().board
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = chosen?.key == subject.key, onCheckedChange = null)
                                Text(subject.name, Modifier.weight(1f).padding(start = 8.dp))
                                if (chosen?.key == subject.key) {
                                    BoardPicker(subject, board) { board = it ?: board }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val spec = chosen?.let { subject -> board?.let { subject.forBoard(it) } }
            Button(
                enabled = spec != null,
                onClick = {
                    CatalogueInstaller.install(
                        state.db, systemNow, listOf(CatalogueInstaller.defaultChoice(spec!!)),
                    )
                    state.dataChanged()
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- finishing

private fun choiceFor(spec: SpecSubject, tiers: Map<String, Tier>, options: Map<String, Boolean>): SubjectChoice =
    SubjectChoice(
        subject = spec,
        tier = tiers[spec.ref],
        groups = spec.groups.filter { it.choice != null && options["${spec.ref}|${it.ref}"] == true }
            .map { it.ref }.toSet(),
    )

private fun install(
    state: AppState,
    specs: List<SpecSubject>,
    byHand: List<String>,
    tiers: Map<String, Tier>,
    options: Map<String, Boolean>,
) {
    CatalogueInstaller.install(state.db, systemNow, specs.map { choiceFor(it, tiers, options) })
    byHand.forEach { state.subjects.add(it, ownSubjectColour(it)) }
    state.dataChanged()
}

private fun ownSubjectColour(name: String): String {
    val palette = listOf("#7E57C2", "#26A69A", "#EF5350", "#42A5F5", "#FFA726", "#66BB6A")
    return palette[(name.hashCode() and 0x7fffffff) % palette.size]
}
