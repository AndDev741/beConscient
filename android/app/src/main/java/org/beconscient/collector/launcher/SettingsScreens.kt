package org.beconscient.collector.launcher

import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/** Blocks, paused apps and the way out to the system and the collector status. */
@Composable
fun LauncherSettingsScreen(
    settings: LauncherSettings,
    apps: List<LaunchableApp>,
    focusAllowed: Set<String>,
    onBack: () -> Unit,
    onEditBlock: (String?) -> Unit,
    onToggleControlled: (String) -> Unit,
    onToggleFocusAllowed: (String) -> Unit,
    onHomeSettings: () -> Unit,
    onCollectorStatus: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val labels = apps.associate { it.packageName to it.label }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("back", color = dim) }
            Title("Blocks")
            Text("A block gives the pause screen its message. Outside every block it asks: ${Pauses.DEFAULT_MESSAGE}", color = dim)
            Spacer(Modifier.height(8.dp))
        }
        items(settings.blocks, key = { it.id }) { b ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEditBlock(b.id) }
                    .padding(vertical = 10.dp),
            ) {
                Text(b.name, fontSize = 20.sp)
                Text("${daysLabel(b.days)} · ${b.range()}${if (b.locks) " · locks the phone" else ""}", color = dim)
                if (b.message.isNotBlank()) Text(b.message, color = dim, maxLines = 2)
            }
        }
        item {
            TextButton(onClick = { onEditBlock(null) }) { Text("Add a block") }
            Title("Paused apps")
            Text("Long-press an app in the list to add or remove it.", color = dim)
            Spacer(Modifier.height(8.dp))
        }
        val controlled = settings.controlled.sortedBy { (labels[it] ?: it).lowercase() }
        items(controlled, key = { it }) { pkg ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels[pkg] ?: pkg, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                TextButton(onClick = { onToggleControlled(pkg) }) { Text("remove", color = dim) }
            }
        }
        if (controlled.isEmpty()) item { Text("None yet.", color = dim) }
        item {
            Title("Allowed during focus")
            Text("Only these open while the phone is locked. Long-press an app in the list to add it.", color = dim)
            Spacer(Modifier.height(8.dp))
        }
        // Only installed apps: the defaults name apps for several phone makers
        val allowed = apps.filter { it.packageName in focusAllowed }
        items(allowed, key = { "allowed-" + it.packageName }) { app ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(app.label, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                TextButton(onClick = { onToggleFocusAllowed(app.packageName) }) { Text("remove", color = dim) }
            }
        }
        if (allowed.isEmpty()) item { Text("None: a focus period blocks every app.", color = dim) }
        item {
            Title("Phone")
            TextButton(onClick = onHomeSettings) { Text("Choose the default home app") }
            TextButton(onClick = onCollectorStatus) { Text("Collector status") }
        }
    }
}

@Composable
private fun Title(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, fontSize = 26.sp, fontWeight = FontWeight.Light)
}

/** Adds a block, or edits [block]. */
@Composable
fun BlockEditor(
    block: Block?,
    apps: List<LaunchableApp>,
    onSave: (Block) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var name by rememberSaveable { mutableStateOf(block?.name.orEmpty()) }
    var message by rememberSaveable { mutableStateOf(block?.message.orEmpty()) }
    var days by remember { mutableStateOf(block?.days ?: DayOfWeek.entries.toSet()) }
    var start by remember { mutableStateOf(block?.start ?: LocalTime.of(19, 0)) }
    var end by remember { mutableStateOf(block?.end ?: LocalTime.of(20, 0)) }
    var alternative by remember { mutableStateOf(block?.alternative) }
    var locks by remember { mutableStateOf(block?.locks ?: false) }
    var picking by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && days.isNotEmpty() && start != end

    fun pickTime(current: LocalTime, set: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> set(LocalTime.of(h, m)) }, current.hour, current.minute, true).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Title(if (block == null) "New block" else "Edit block")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(Block.NAME_MAX) },
            label = { Text("Name") },
            placeholder = { Text("Family") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Days", color = dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in DayOfWeek.entries) {
                FilterChip(
                    selected = d in days,
                    onClick = { days = if (d in days) days - d else days + d },
                    label = { Text(d.short()) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pickTime(start) { start = it } }) { Text("From ${start.hhmm()}") }
            OutlinedButton(onClick = { pickTime(end) { end = it } }) { Text("To ${end.hhmm()}") }
        }
        if (start == end) {
            Text("Start and end must differ.", color = dim)
        } else if (!end.isAfter(start)) {
            Text("Ends the next day.", color = dim)
        }
        OutlinedTextField(
            value = message,
            onValueChange = { message = it.take(Block.MESSAGE_MAX) },
            label = { Text("Message on the pause screen") },
            placeholder = { Text("This hour is for the family. Is this worth it?") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Alternative app, offered as a button on the pause screen", color = dim)
        val altLabel = alternative?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picking = true }) { Text(altLabel ?: "None") }
            if (alternative != null) TextButton(onClick = { alternative = null }) { Text("remove", color = dim) }
        }
        FilterChip(
            selected = locks,
            onClick = { locks = !locks },
            label = { Text("Lock the phone during this block") },
        )
        if (locks) Text("Only the apps allowed during focus open, and other notifications wait until the block ends.", color = dim)
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = valid,
            onClick = {
                onSave(
                    Block(
                        id = block?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        days = days,
                        start = start,
                        end = end,
                        message = message.trim(),
                        alternative = alternative,
                        locks = locks,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (block != null) {
            TextButton(onClick = { onDelete(block.id) }, modifier = Modifier.fillMaxWidth()) { Text("Delete block") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = dim) }
    }

    if (picking) {
        AppPicker(apps, onPick = { alternative = it.packageName; picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun AppPicker(apps: List<LaunchableApp>, onPick: (LaunchableApp) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alternative app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Text(
                            app.label,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}
