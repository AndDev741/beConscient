package org.beconscient.collector.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.beconscient.collector.setup.Step
import org.beconscient.collector.setup.StepState
import org.beconscient.collector.setup.StepStatus

internal fun explanation(step: Step): String = when (step) {
    Step.PAIRING ->
        "On your beConscient page, open Devices and add this phone. The page shows a one-time code " +
            "(XXXX-XXXX, valid for 15 minutes). Type it here with a name for this phone. The phone makes " +
            "its own secret; the server keeps only its hash."
    Step.USAGE ->
        "Lets the collector read the records Android already keeps about which app is in front, " +
            "when the screen turns on and off, and when the phone locks. This is how screen time is " +
            "measured. The first read reaches back about 7 days."
    Step.NOTIFICATIONS ->
        "Records when a notification arrives, from which app, and whether you tapped or dismissed it. " +
            "The title, the text and the sender never leave the phone."
    Step.ACCESSIBILITY ->
        "Optional. Watches only the YouTube app and reads only layout ids, never text, to tell Shorts " +
            "from the rest of YouTube. Without it, Shorts time counts as YouTube."
    Step.BATTERY ->
        "Lets the collector run its 15-minute reads and uploads in the background. Without it some " +
            "phones stop the app, and Android keeps usage records for only about a week."
    Step.UNUSED_APPS ->
        "Android pauses apps you do not open for a few months and removes their permissions. You rarely " +
            "open this app, so turn off \"Pause app activity if unused\" for it."
}

private const val RESTRICTED_HELP =
    "If the switch is greyed out and Android says \"Restricted setting\", the app was installed from a " +
        "file. Open App info, tap the three-dot menu, choose \"Allow restricted settings\", confirm, and " +
        "come back. Installing with adb install avoids this step."

@Composable
fun OnboardingScreen(
    state: UiState,
    onOpenStep: (Step) -> Unit,
    onOpenAppInfo: () -> Unit,
    onSkip: (Step, Boolean) -> Unit,
    onPair: (String, String) -> Unit,
    onDismissPairing: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Set up beConscient", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Each step says what it is for. You can skip a step; the status screen keeps a warning " +
                    "until it is done, and you can come back here at any time.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(state.steps, key = { it.step.name }) { s ->
            StepCard(
                state = s,
                pairing = state.pairing,
                status = state.status,
                onOpen = { onOpenStep(s.step) },
                onOpenAppInfo = onOpenAppInfo,
                onSkip = { skipped -> onSkip(s.step, skipped) },
                onPair = onPair,
            )
        }
        item {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.steps.any { it.warning }) "Continue with warnings" else "Done")
            }
        }
    }

    when (val p = state.pairing) {
        PairingUi.CodeRejected -> AlertDialog(
            onDismissRequest = onDismissPairing,
            confirmButton = { TextButton(onClick = onDismissPairing) { Text("OK") } },
            title = { Text("Code already used or not valid") },
            text = {
                Text(
                    "The server did not accept this code. A code works once and expires after 15 minutes; " +
                        "after ten failed attempts all open codes stop working. Create a new code on the " +
                        "Devices page and try again.",
                )
            },
        )
        is PairingUi.Paired -> if (!p.match) {
            AlertDialog(
                onDismissRequest = onDismissPairing,
                confirmButton = { TextButton(onClick = onDismissPairing) { Text("I understand") } },
                title = { Text("Fingerprints differ") },
                text = {
                    Text(
                        "This phone computed ${p.fingerprint} but the server reported ${p.serverFingerprint}. " +
                            "Do not trust this pairing: revoke it on the Devices page and pair again.",
                    )
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun StepCard(
    state: StepState,
    pairing: PairingUi,
    status: StatusSnapshot?,
    onOpen: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onPair: (String, String) -> Unit,
) {
    val step = state.step
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.needsWork) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${step.ordinal + 1}. ${step.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(state.status)
            }
            Text(explanation(step), style = MaterialTheme.typography.bodyMedium)

            if (step == Step.PAIRING) {
                PairingSection(state, pairing, status, onPair)
            } else if (state.status != StepStatus.DONE && state.status != StepStatus.NOT_APPLICABLE) {
                Button(onClick = onOpen) { Text("Open settings") }
                if (step == Step.NOTIFICATIONS || step == Step.ACCESSIBILITY) {
                    Text(RESTRICTED_HELP, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpenAppInfo) { Text("Open App info") }
                }
            }
            if (step == Step.UNUSED_APPS && state.status == StepStatus.NOT_APPLICABLE) {
                Text("Android ${Build.VERSION.RELEASE} has no such setting.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.status == StepStatus.PENDING || state.status == StepStatus.DENIED) {
                TextButton(onClick = { onSkip(true) }) { Text("Skip for now") }
            } else if (state.status == StepStatus.SKIPPED) {
                Text(
                    if (step.required) "Skipped. Data from this step is missing until you finish it." else "Skipped.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onSkip(false) }) { Text("Do it now") }
            }
        }
    }
}

@Composable
private fun PairingSection(state: StepState, pairing: PairingUi, status: StatusSnapshot?, onPair: (String, String) -> Unit) {
    var repair by remember { mutableStateOf(false) }
    LaunchedEffect(pairing) { if (pairing is PairingUi.Paired) repair = false }
    if (state.status == StepStatus.DONE && !repair) {
        val fp = status?.fingerprint.orEmpty()
        Text("Paired as ${status?.deviceName.orEmpty()}.", style = MaterialTheme.typography.bodyMedium)
        Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
        Text(fp, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            "Compare it with the fingerprint shown for this phone on the Devices page. They must be the same.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { repair = true }) { Text("Pair again") }
        return
    }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android phone") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.take(9).uppercase() },
        label = { Text("Pairing code (XXXX-XXXX)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(64) },
        label = { Text("Name for this phone") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onPair(code, name) }, enabled = pairing != PairingUi.Working && code.isNotBlank()) {
            Text("Pair")
        }
        if (pairing == PairingUi.Working) CircularProgressIndicator(Modifier.size(24.dp))
    }
    when (pairing) {
        PairingUi.InvalidCode -> ErrorText("The code has 8 characters from 0-9 and A-Z, for example 7KQ2-M9XD.")
        is PairingUi.Failed -> ErrorText(
            when (pairing.reason) {
                "network" -> "Could not reach the server. Check the connection and try again."
                "bad_response" -> "The answer did not come from your server. Check the network (captive portal?) and try again."
                "rate_limited" -> "Too many attempts. Wait a moment and try again."
                else -> "Pairing failed (${pairing.reason})."
            },
        )
        else -> Unit
    }
}

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

@Composable
internal fun StatusChip(status: StepStatus) {
    val (label, color) = when (status) {
        StepStatus.DONE -> "Done" to Color(0xFF2E7D32)
        StepStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        StepStatus.DENIED -> "Not granted" to MaterialTheme.colorScheme.error
        StepStatus.SKIPPED -> "Skipped" to Color(0xFFB26A00)
        StepStatus.NOT_APPLICABLE -> "Not needed" to MaterialTheme.colorScheme.outline
    }
    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
}
