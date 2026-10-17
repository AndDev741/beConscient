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
 * The message, then the app. Going back and the alternative work at once;
 * opening the app itself waits [Pauses.WAIT_MS].
 */
@Composable
fun PauseScreen(ui: PauseUi, onChoose: (Events.PauseOutcome) -> Unit) {
    var left by remember(ui.shownAt) { mutableLongStateOf(Pauses.WAIT_MS) }
    LaunchedEffect(ui.shownAt) {
        while (left > 0) {
            delay(TICK_MS)
            left = (Pauses.WAIT_MS - (System.currentTimeMillis() - ui.shownAt)).coerceAtLeast(0)
        }
    }
    BackHandler { onChoose(Events.PauseOutcome.BACKED_OUT) }
    val seconds = (left + 999) / 1000
    val block = ui.pause.block

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("Opening ${ui.app.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        if (block != null) {
            Text("${block.range()} · ${block.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(ui.pause.message, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.weight(1f))
        val alternative = ui.alternative
        if (alternative != null) {
            Button(onClick = { onChoose(Events.PauseOutcome.ALTERNATIVE) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open ${alternative.label}", fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = { onChoose(Events.PauseOutcome.OPENED) },
            enabled = left == 0L,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (left > 0) "Open ${ui.app.label} in $seconds" else "Open ${ui.app.label}", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onChoose(Events.PauseOutcome.BACKED_OUT) }, modifier = Modifier.fillMaxWidth()) {
            Text("Not now", fontSize = 18.sp)
        }
    }
}

private const val TICK_MS = 200L
