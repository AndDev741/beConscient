package org.beconscient.collector.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beconscient.collector.events.Events

/**
 * The message, how often the app was opened today, then the app. Going back,
 * resting and the alternative work at once; opening the app itself waits
 * [Pause.waitMs] and then asks what for.
 */
@Composable
fun PauseScreen(ui: PauseUi, onChoose: (Events.PauseOutcome, Events.PauseIntent?) -> Unit) {
    val wait = ui.pause.waitMs
    var left by remember(ui.shownAt) { mutableLongStateOf(wait) }
    LaunchedEffect(ui.shownAt) {
        while (left > 0) {
            delay(TICK_MS)
            left = (wait - (System.currentTimeMillis() - ui.shownAt)).coerceAtLeast(0)
        }
    }
    BackHandler { onChoose(Events.PauseOutcome.BACKED_OUT, null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val seconds = (left + 999) / 1000
    val block = ui.pause.block

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("Opening ${ui.app.label}", color = dim, fontSize = 16.sp)
        if (block != null) {
            Text("${block.range()} · ${block.name}", color = dim, fontSize = 16.sp)
        }
        Text(timesToday(ui.pause, ui.shownAt), color = dim, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Text(ui.pause.message, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.weight(1f))
        val alternative = ui.alternative
        if (alternative != null) {
            Button(onClick = { onChoose(Events.PauseOutcome.ALTERNATIVE, null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open ${alternative.label}", fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { onChoose(Events.PauseOutcome.REST, null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Rest 3 minutes instead", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (left > 0) {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Open ${ui.app.label} in $seconds", fontSize = 18.sp)
            }
        } else {
            Text("Open ${ui.app.label} to", color = dim, fontSize = 16.sp)
            for ((intent, label) in INTENTS) {
                OutlinedButton(onClick = { onChoose(Events.PauseOutcome.OPENED, intent) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onChoose(Events.PauseOutcome.BACKED_OUT, null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Not now", fontSize = 18.sp)
        }
    }
}

private val INTENTS = listOf(
    Events.PauseIntent.REPLY to "reply to someone",
    Events.PauseIntent.SPECIFIC to "do something specific",
    Events.PauseIntent.HABIT to "nothing in particular, habit",
)

/** "First time today", or "4th time today, the last one 12 min ago". */
internal fun timesToday(pause: Pause, now: Long): String {
    if (pause.openCount <= 1) return "First time today"
    val n = pause.openCount
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    val ago = pause.previousAt?.let { ((now - it) / 60_000).coerceAtLeast(0) }
    return when (ago) {
        null -> "$n$suffix time today"
        0L -> "$n$suffix time today, the last one just now"
        else -> "$n$suffix time today, the last one $ago min ago"
    }
}

private const val TICK_MS = 200L
