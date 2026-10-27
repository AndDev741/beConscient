package org.beconscient.collector.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beconscient.collector.events.Events.FocusKind
import org.beconscient.collector.focus.BlockedApp
import org.beconscient.collector.focus.FocusMachine
import org.beconscient.collector.focus.FocusPhase
import org.beconscient.collector.focus.FocusToday
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "23:41" for a countdown. */
internal fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

internal fun clockAt(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** "2 focus rounds, 1 h 05 min · 1 rest", or null before the first one today. */
internal fun todayLine(t: FocusToday): String? {
    if (t.rounds == 0 && t.rests == 0) return null
    val parts = mutableListOf<String>()
    if (t.rounds > 0) {
        val time = if (t.minutes >= 60) "${t.minutes / 60} h %02d min".format(t.minutes % 60) else "${t.minutes} min"
        parts += "${t.rounds} focus ${if (t.rounds == 1) "round" else "rounds"}, $time"
    }
    if (t.rests > 0) parts += "${t.rests} ${if (t.rests == 1) "rest" else "rests"}"
    return "Today: " + parts.joinToString(" · ")
}

/**
 * The home screen during a focus period: the time left, the allowed apps and,
 * right after one was sent back, which app is blocked and until when.
 */
@Composable
fun FocusScreen(
    phase: FocusPhase.Locked,
    nowMs: Long,
    allowed: List<LaunchableApp>,
    lastBlocked: BlockedApp?,
    labelOf: (String) -> String,
    onOpen: (LaunchableApp) -> Unit,
) {
    BackHandler { }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val p = phase.period
    val rest = p.kind == FocusKind.REST
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(mmss(p.endsAt - nowMs), fontSize = 72.sp, fontWeight = FontWeight.Light)
        val title = when (p.kind) {
            FocusKind.REST -> "Rest. Look away from the screen and breathe."
            FocusKind.SCHEDULED -> "${p.block ?: "Block"} · locked until ${clockAt(p.endsAt)}"
            FocusKind.MANUAL -> if (p.cycle > 1) "Focus, round ${p.cycle} · until ${clockAt(p.endsAt)}" else "Focus · until ${clockAt(p.endsAt)}"
        }
        Text(title, color = dim, fontSize = 16.sp)
        if (p.heldNotifications > 0) {
            Text("${p.heldNotifications} notifications waiting for the end", color = dim, fontSize = 16.sp)
        }
        val blocked = lastBlocked?.takeIf { nowMs - it.at < BLOCKED_NOTICE_MS }
        if (blocked != null) {
            Spacer(Modifier.height(16.dp))
            Text("${labelOf(blocked.packageName)} is blocked until ${clockAt(p.endsAt)}.", fontSize = 20.sp)
        }
        Spacer(Modifier.weight(1f))
        if (!rest) {
            LazyColumn(Modifier.weight(2f)) {
                items(allowed, key = { it.packageName }) { app ->
                    Text(
                        app.label,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(app) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private const val BLOCKED_NOTICE_MS = 15_000L

/** A manual period is over: five minutes and another round, or stop. The phone stays locked until then. */
@Composable
fun DecideScreen(phase: FocusPhase.Deciding, today: FocusToday, onBreak: () -> Unit, onStop: () -> Unit) {
    BackHandler { }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val p = phase.period
    val minutes = p.plannedMs / 60_000
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("$minutes minutes done.", fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(12.dp))
        if (p.heldNotifications > 0) Text("${p.heldNotifications} held notifications are back in the shade.", color = dim, fontSize = 16.sp)
        if (p.blockedOpens > 0) Text("You reached for a blocked app ${p.blockedOpens} times.", color = dim, fontSize = 16.sp)
        todayLine(today)?.let { Text(it, color = dim, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onBreak, modifier = Modifier.fillMaxWidth()) {
            Text("5 minutes, then $minutes more", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop for now", fontSize = 18.sp)
        }
    }
}

/** Picks the length of a manual period. [blockingWorks] is false when the accessibility service is off. */
@Composable
fun StartFocusDialog(blockingWorks: Boolean, onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(30) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focus") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in FocusMachine.MANUAL_MINUTES) {
                        FilterChip(selected = m == minutes, onClick = { minutes = m }, label = { Text("$m min") })
                    }
                }
                Text(
                    "Only your allowed apps open, and notifications from the other apps wait until the end. " +
                        "There is no way out before the time is up. Then you choose: 5 minutes and another round, or stop.",
                    color = dim,
                )
                if (!blockingWorks) {
                    Text("The beConscient accessibility service is off, so apps cannot be blocked. Turn it on in Collector status first.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(minutes) }) { Text("Lock for $minutes min") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = dim) } },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}
