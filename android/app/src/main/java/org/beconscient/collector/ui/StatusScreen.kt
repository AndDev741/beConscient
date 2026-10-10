package org.beconscient.collector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.beconscient.collector.events.Events
import org.beconscient.collector.setup.StepState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun StatusScreen(
    state: UiState,
    onFix: () -> Unit,
    onUploadNow: () -> Unit,
    countResendable: suspend (Long) -> Int,
    onResend: (Long) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status ?: return
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("beConscient collector", style = MaterialTheme.typography.headlineSmall) }
        val warnings = state.steps.filter { it.warning }
        if (warnings.isNotEmpty()) item { WarningCard(warnings, onFix) }
        state.notice?.let { notice ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp)) {
                        Text(notice, Modifier.weight(1f))
                        TextButton(onClick = onDismissNotice) { Text("OK") }
                    }
                }
            }
        }
        item { SensorsCard(status) }
        item { QueueCard(status, onUploadNow, countResendable, onResend) }
        item { DeviceCard(status, onFix) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun WarningCard(warnings: List<StepState>, onFix: () -> Unit) {
    Section("Setup is not complete") {
        warnings.forEach {
            Text("• ${it.step.title}: ${it.status.name.lowercase().replace('_', ' ')}", color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onFix) { Text("Finish setup") }
    }
}

@Composable
private fun SensorsCard(status: StatusSnapshot) {
    Section("Sensors") {
        status.sensors.forEach { (name, s) ->
            val color = if (s.state == Events.SensorState.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            Text("$name: ${s.state.wire}", color = color, style = MaterialTheme.typography.bodyLarge)
            s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            s.lastEventAt?.let { Text("last event ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("Usage read up to ${formatTime(status.watermark)}", style = MaterialTheme.typography.bodySmall)
        Text("Launcher: ${status.homePackage ?: "none set"}", style = MaterialTheme.typography.bodySmall)
        if (status.gaps.isNotEmpty()) {
            Text("Recent gaps", style = MaterialTheme.typography.labelLarge)
            status.gaps.forEach {
                Text(
                    "${it.sensor}: ${formatTime(it.fromMs)} to ${formatTime(it.toMs)}, ${it.recovered} recovered",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueCard(
    status: StatusSnapshot,
    onUploadNow: () -> Unit,
    countResendable: suspend (Long) -> Int,
    onResend: (Long) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val scope = rememberCoroutineScope()

    Section("Queue and upload") {
        Text("Waiting to send: ${status.pending + status.inBatch}")
        Text("Acknowledged, kept 14 days: ${status.acked}")
        Text("Rejected by the server: ${status.rejected}")
        Text("Last upload: ${formatTime(status.lastUploadOkAt)}")
        Text("Last attempt: ${formatTime(status.lastAttemptAt)}", style = MaterialTheme.typography.bodySmall)
        explainUploadError(status.lastError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUploadNow, enabled = status.paired) { Text("Upload now") }
            OutlinedButton(onClick = { picking = true }) { Text("Resend since…") }
        }
        Text(
            "Resend after a server restore: acknowledged events from the chosen day on go out again, " +
                "and the server marks the ones it already has as duplicates.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (picking) {
        val today = LocalDate.now()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val utc = pickerState.selectedDateMillis
                    picking = false
                    if (utc != null) {
                        val day = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val since = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        scope.launch { confirm = since to countResendable(since) }
                    }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    confirm?.let { (since, count) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Resend acknowledged events?") },
            text = {
                Text(
                    if (count == 0) {
                        "No acknowledged events from ${formatTime(since)} are still on the phone."
                    } else {
                        "$count acknowledged events from ${formatTime(since)} on will be sent again in new batches."
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = count > 0, onClick = {
                    confirm = null
                    onResend(since)
                }) { Text("Resend") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceCard(status: StatusSnapshot, onReview: () -> Unit) {
    Section("This installation") {
        if (status.paired) {
            Text("Name: ${status.deviceName.orEmpty()}")
            Text("Fingerprint: ${status.fingerprint.orEmpty()}", fontFamily = FontFamily.Monospace)
            if (!status.serverFingerprint.isNullOrEmpty() && !status.serverFingerprint.equals(status.fingerprint, ignoreCase = true)) {
                Text("The server reported ${status.serverFingerprint}. Pair again.", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text("Not paired. Events stay on this phone until you pair.", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = onReview) { Text("Review setup") }
    }
}
