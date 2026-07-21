package uk.co.riera.talkbalance.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.riera.talkbalance.data.DeleteAllResult

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onDeleteAllData: suspend () -> DeleteAllResult,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nudgeEnabled by viewModel.nudgeEnabled.collectAsStateWithLifecycle(false)
    val nudgeSeconds by viewModel.nudgeSeconds.collectAsStateWithLifecycle(45)
    val matchThreshold by viewModel.matchThreshold.collectAsStateWithLifecycle(0.50f)
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle(true)

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<DeleteAllResult?>(null) }
    var developerExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showDeleteConfirm) {
        DeleteAllDataDialog(
            onConfirm = {
                showDeleteConfirm = false
                deleting = true
                scope.launch {
                    val result = onDeleteAllData()
                    deleting = false
                    deleteResult = result
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    deleteResult?.let { result ->
        AlertDialog(
            onDismissRequest = { deleteResult = null },
            title = { Text(if (result.allSucceeded) "Data deleted" else "Not fully deleted") },
            text = { Text(result.summary) },
            confirmButton = { TextButton(onClick = { deleteResult = null }) { Text("OK") } },
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // Nudge section
        SettingCard(title = "Nudge") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingToggle(
                    label = "Enable nudge",
                    checked = nudgeEnabled,
                    onCheckedChange = { viewModel.setNudgeEnabled(it) },
                )

                if (nudgeEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nudge interval: $nudgeSeconds seconds")
                        Slider(
                            value = nudgeSeconds.toFloat(),
                            onValueChange = { viewModel.setNudgeSeconds(it.toInt()) },
                            valueRange = 15f..120f,
                            steps = 20,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Remind if talking less than this duration",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Keep screen on section
        SettingCard(title = "Screen") {
            SettingToggle(
                label = "Keep screen on during session",
                checked = keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
            )
        }

        // Developer section
        SettingCard(
            title = "Developer",
            expandable = true,
            expanded = developerExpanded,
            onExpandedChange = { developerExpanded = it },
        ) {
            if (developerExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Match threshold: %.2f".format(matchThreshold))
                        Slider(
                            value = matchThreshold,
                            onValueChange = { viewModel.setMatchThreshold(it) },
                            valueRange = 0.30f..0.80f,
                            steps = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Higher = stricter matching (fewer false matches). Controls speaker recognition accuracy.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Privacy section
        SettingCard(title = "Privacy") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Talk Balance does not request Internet permission and never sends audio " +
                        "off the device — microphone audio is processed in memory and is never " +
                        "written to disk. You can verify the missing Internet permission yourself " +
                        "in system settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Delete all data section
        SettingCard(title = "Data") {
            Button(
                onClick = { showDeleteConfirm = true },
                enabled = !deleting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (deleting) "Deleting…" else "Delete all data")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .apply {
                        if (expandable) {
                            clickable { onExpandedChange(!expanded) }
                        } else {
                            this
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (expandable) {
                    Text(if (expanded) "▼" else "▶")
                }
            }
            if (!expandable || expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeleteAllDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all data?") },
        text = {
            Text(
                "Removes all Talk Balance data from the app.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
