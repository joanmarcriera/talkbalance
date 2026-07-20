package uk.co.riera.talkbalance.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.riera.talkbalance.recording.RecordingContract
import uk.co.riera.talkbalance.recording.RecordingController
import uk.co.riera.talkbalance.recording.RecordingStatus

private enum class SetupStep { GROUP, CONSENT, READY }

@Composable
fun TalkBalanceApp() {
    MaterialTheme {
        var step by remember { mutableStateOf(SetupStep.GROUP) }
        val participants = remember { mutableStateListOf("", "") }
        var groupName by remember { mutableStateOf("") }

        Scaffold(topBar = { AppBar() }) { padding ->
            when (step) {
                SetupStep.GROUP -> GroupSetup(
                    groupName = groupName,
                    onGroupNameChange = { groupName = it },
                    participants = participants,
                    onContinue = { step = SetupStep.CONSENT },
                    modifier = Modifier.padding(padding),
                )
                SetupStep.CONSENT -> ConsentScreen(
                    onBack = { step = SetupStep.GROUP },
                    onContinue = { step = SetupStep.READY },
                    modifier = Modifier.padding(padding),
                )
                SetupStep.READY -> ReadyScreen(
                    groupName = groupName.ifBlank { "This table" },
                    participants = participants.filter { it.isNotBlank() },
                    onBack = { step = SetupStep.CONSENT },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar() = TopAppBar(title = { Text("Talk Balance") })

@Composable
private fun GroupSetup(
    groupName: String,
    onGroupNameChange: (String) -> Unit,
    participants: MutableList<String>,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Who is at the table?", style = MaterialTheme.typography.headlineMedium)
        Text("Add two to six people. Names stay on this phone.")
        OutlinedTextField(
            value = groupName,
            onValueChange = onGroupNameChange,
            label = { Text("Group name (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        participants.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { participants[index] = it },
                label = { Text("Participant ${index + 1}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = participants.size < 6,
                onClick = { participants.add("") },
            ) { Text("Add person") }
            OutlinedButton(
                enabled = participants.size > 2,
                onClick = { participants.removeAt(participants.lastIndex) },
            ) { Text("Remove") }
        }
        Button(
            enabled = participants.size in 2..6 && participants.all { it.trim().isNotEmpty() } &&
                participants.map { it.trim().lowercase() }.distinct().size == participants.size,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

@Composable
private fun ConsentScreen(onBack: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    var agreed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Everyone must agree", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Talk Balance records this conversation and analyses speakers entirely on this phone.")
                Text("Audio and names never leave the device. Only aggregate statistics are retained.")
                Text("Anyone can ask to stop or delete the recording at any time.")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("Everyone present has agreed to be recorded.")
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        Button(enabled = agreed, onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Start voice check")
        }
    }
}

@Composable
private fun ReadyScreen(
    groupName: String,
    participants: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recordingStatus by RecordingController.status.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf(false) }
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionError = false
            ContextCompat.startForegroundService(
                context,
                RecordingContract.intent(context, RecordingContract.ACTION_START),
            )
        } else permissionError = true
    }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Ready to record", style = MaterialTheme.typography.headlineMedium)
        Text(groupName, style = MaterialTheme.typography.titleLarge)
        Text("Each person will speak for five seconds, followed by a two-second gap.")
        participants.forEachIndexed { index, name -> Text("${index + 1}. $name") }
        Spacer(Modifier.height(8.dp))
        RecordingStatusCard(recordingStatus)
        if (permissionError) Text("Microphone permission is required to record.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        when (recordingStatus) {
            RecordingStatus.Idle, is RecordingStatus.Failed, is RecordingStatus.Finished ->
                Button(onClick = { permissionLauncher.launch(permissions) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start recording")
                }
            is RecordingStatus.Recording -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { context.startService(RecordingContract.intent(context, RecordingContract.ACTION_PAUSE)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pause") }
                Button(
                    onClick = { context.startService(RecordingContract.intent(context, RecordingContract.ACTION_FINISH)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Finish") }
            }
            is RecordingStatus.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { context.startService(RecordingContract.intent(context, RecordingContract.ACTION_RESUME)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Resume") }
                Button(
                    onClick = { context.startService(RecordingContract.intent(context, RecordingContract.ACTION_FINISH)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun RecordingStatusCard(status: RecordingStatus) {
    val message = when (status) {
        RecordingStatus.Idle -> "The phone will keep recording when the screen is locked."
        is RecordingStatus.Recording -> "Recording • ${formatDuration(status.elapsedMs)}"
        is RecordingStatus.Paused -> "Paused • ${formatDuration(status.elapsedMs)}"
        is RecordingStatus.Finished -> "Saved locally • ${formatDuration(status.result.durationMs)}\nSHA-256 verified."
        is RecordingStatus.Failed -> "${status.message} (${status.code})"
    }
    Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(20.dp)) }
}

private fun formatDuration(value: Long): String {
    val seconds = value / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
