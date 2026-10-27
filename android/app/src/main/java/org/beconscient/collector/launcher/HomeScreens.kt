package org.beconscient.collector.launcher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The local time, refreshed on the minute and whenever the home screen comes back. */
@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleResumeEffect(Unit) {
        now = LocalDateTime.now()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50)
            now = LocalDateTime.now()
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L

/**
 * Clock, the block in force, today's focus and the pinned apps as text. Swipe
 * up for every app. [breakLeft] is the time left in a break and the length of
 * the round after it.
 */
@Composable
fun HomeScreen(
    now: LocalDateTime,
    settings: LauncherSettings,
    apps: List<LaunchableApp>,
    breakLeft: Pair<Long, Long>?,
    today: String?,
    onOpen: (LaunchableApp) -> Unit,
    onMenu: (LaunchableApp) -> Unit,
    onAllApps: () -> Unit,
    onSettings: () -> Unit,
    onFocus: () -> Unit,
) {
    val context = LocalContext.current
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val block = settings.blockAt(now)
    val byPackage = apps.associateBy { it.packageName }
    val pinned = settings.pinned.mapNotNull { byPackage[it] }
    val swipeUp by rememberUpdatedState(onAllApps)
    // Home is the root: back has nowhere to go
    BackHandler { }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -SWIPE_DP.dp.toPx()) swipeUp() },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(now.format(DateTimeFormatter.ofPattern(timePattern)), fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", LocalLocale.current.platformLocale)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
        )
        if (block != null) {
            Spacer(Modifier.height(12.dp))
            Text("${block.range()} · ${block.name}", fontSize = 16.sp)
        }
        if (breakLeft != null) {
            Spacer(Modifier.height(12.dp))
            Text("Break · ${mmss(breakLeft.first)} left, then ${breakLeft.second / 60_000} min of focus", fontSize = 16.sp)
        }
        if (today != null) {
            Spacer(Modifier.height(12.dp))
            Text(today, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        if (pinned.isEmpty()) {
            Text(
                "Swipe up for your apps. Long-press one to pin it here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
            )
        }
        for (app in pinned) {
            Text(
                app.label,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onMenu(app) })
                    .padding(vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSettings) { Text("settings", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (breakLeft == null) TextButton(onClick = onFocus) { Text("focus", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onAllApps) { Text("all apps", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private const val SWIPE_DP = 80

/** Every app as text, with search. Go on the keyboard opens the first match. */
@Composable
fun AllAppsScreen(
    apps: List<LaunchableApp>,
    settings: LauncherSettings,
    onOpen: (LaunchableApp) -> Unit,
    onMenu: (LaunchableApp) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val shown = if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 32.dp, vertical = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(onOpen) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.packageName }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onMenu(app) })
                        .padding(vertical = 12.dp),
                ) {
                    Text(app.label, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                    if (app.packageName in settings.controlled) {
                        Text("pause", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Long-press menu: pin to home, pause before opening, allow during focus, app info. */
@Composable
fun AppMenu(
    app: LaunchableApp,
    settings: LauncherSettings,
    allowedInFocus: Boolean,
    onTogglePinned: () -> Unit,
    onToggleControlled: () -> Unit,
    onToggleAllowed: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pinned = app.packageName in settings.pinned
    val controlled = app.packageName in settings.controlled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column {
                MenuItem(if (pinned) "Unpin from home" else "Pin to home", onTogglePinned)
                MenuItem(if (controlled) "Stop pausing before opening" else "Pause before opening", onToggleControlled)
                MenuItem(if (allowedInFocus) "Block during focus" else "Allow during focus", onToggleAllowed)
                MenuItem("App info", onAppInfo)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 18.sp, modifier = Modifier.fillMaxWidth())
    }
}
