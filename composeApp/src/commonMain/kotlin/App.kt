package revision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import revision.core.db.RevisionDatabase
import revision.core.formatClock
import revision.core.formatTimeOfDay

/** True on phone-width screens; screens use it to stack controls instead of laying them in a row. */
val LocalCompact = compositionLocalOf { false }

@Composable
fun App(db: RevisionDatabase, files: FileAccess = NoFileAccess, syncPlatform: SyncPlatform = NoSyncPlatform, state: AppState = androidx.compose.runtime.remember { AppState(db) }) {

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sync = androidx.compose.runtime.remember { SyncManager(db, syncPlatform, scope) }
    LaunchedEffect(Unit) {
        if (sync.enabled) sync.syncNow()
        sync.pollLoop()
    }
    LaunchedEffect(state.historyVersion) { sync.changed() }
    LaunchedEffect(sync.pulledVersion) { if (sync.pulledVersion > 0) state.dataChanged() }

    // Redraw once a second. Elapsed time is recomputed from stored timestamps each time,
    // so a missed tick (sleep, lag) cannot make the clock wrong.
    LaunchedEffect(Unit) {
        var beats = 0
        while (true) {
            state.onTick()
            if (++beats % 15 == 0) state.heartbeat()
            delay(1000)
        }
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
          BoxWithConstraints(Modifier.fillMaxSize()) {
           CompositionLocalProvider(LocalCompact provides (maxWidth < 600.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Keep clear of the status bar / camera cut-out; the nav bar handles the bottom edge.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .imePadding(),
            ) {
                ActiveBanner(state)
                Box(Modifier.weight(1f)) {
                    when (state.screen) {
                        Screen.Today -> TodayScreen(state)
                        Screen.Timer -> TimerScreen(state)
                        Screen.History -> HistoryScreen(state)
                        Screen.LogPast -> LogPastScreen(state)
                        Screen.Topics -> ManageScreen(state)
                        Screen.Stats -> StatsScreen(state)
                        Screen.Settings -> SettingsScreen(state, files, sync)
                    }
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = state.screen == Screen.Today,
                        onClick = { state.screen = Screen.Today },
                        icon = { Text("★") }, label = { Text("Today") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Timer,
                        onClick = { state.screen = Screen.Timer },
                        icon = { Text("⏱") }, label = { Text("Timer") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.History || state.screen == Screen.LogPast,
                        onClick = { state.screen = Screen.History },
                        icon = { Text("☰") }, label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Topics,
                        onClick = { state.screen = Screen.Topics },
                        icon = { Text("✎") }, label = { Text("Topics") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Stats,
                        onClick = { state.screen = Screen.Stats },
                        icon = { Text("▮") }, label = { Text("Stats") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Settings,
                        onClick = { state.screen = Screen.Settings },
                        icon = { Text("⚙") }, label = { Text("Settings") },
                    )
                }
            }
            state.dangling?.let { RecoveryDialog(state, it) }
           }
          }
        }
    }
}

/** Visible from every screen while a session exists, so it is never forgotten. */
@Composable
private fun ActiveBanner(state: AppState) {
    val active = state.active ?: return
    if (state.screen == Screen.Timer) return
    val subject = state.subjects.get(active.subjectId)
    val current = active.topics.firstOrNull { it.sessionTopicId == active.runningSessionTopicId }
        ?: active.topics.firstOrNull { it.sessionTopicId == active.lastSessionTopicId }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { state.screen = Screen.Timer }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(parseColour(subject?.colour ?: "#888888"), CircleShape))
        Text(
            "${if (active.isPaused) "Paused" else "Revising"}: ${subject?.name ?: ""} — ${current?.title ?: ""}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(formatClock(active.totalMs), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RecoveryDialog(state: AppState, d: revision.core.timer.DanglingSession) {
    val subject = state.subjects.get(d.subjectId)?.name ?: "a session"
    val since = formatTimeOfDay(d.segmentStartedAt)
    val lastAlive = formatTimeOfDay(d.lastAliveAt)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("You had a session running since $since") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The app closed unexpectedly during your $subject session. The last time it was running was about $lastAlive.")
                Text("Time after that isn't counted, so a sleeping PC doesn't inflate your revision time.")
                if (d.implausiblyLong) {
                    Text("That was a long time ago, so discarding is suggested.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (d.implausiblyLong) Button(onClick = { state.resolveDangling(DanglingAction.Discard) }) { Text("Discard") }
            else Button(onClick = { state.resolveDangling(DanglingAction.Resume) }) { Text("Resume") }
        },
        dismissButton = {
            Row {
                if (d.implausiblyLong) TextButton(onClick = { state.resolveDangling(DanglingAction.Resume) }) { Text("Resume") }
                else TextButton(onClick = { state.resolveDangling(DanglingAction.Discard) }) { Text("Discard") }
                TextButton(onClick = { state.resolveDangling(DanglingAction.Save) }) { Text("Save as-is") }
            }
        },
    )
}
