package uk.co.riera.talkbalance.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.riera.talkbalance.BuildConfig
import uk.co.riera.talkbalance.TalkBalanceApplication
import uk.co.riera.talkbalance.bench.BenchScreen
import uk.co.riera.talkbalance.data.PersonEntity
import uk.co.riera.talkbalance.session.Mode
import uk.co.riera.talkbalance.session.PersistState
import uk.co.riera.talkbalance.session.SessionResult
import uk.co.riera.talkbalance.session.SessionStats
import uk.co.riera.talkbalance.session.balanceTarget
import uk.co.riera.talkbalance.settings.SettingsScreen
import uk.co.riera.talkbalance.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Top-level navigation state for the real session flow. */
private sealed interface Screen {
    data object Home : Screen

    /** [nonce] makes the enrollment ViewModel unique per entry (see [TalkBalanceApp]). */
    data class Enroll(val nonce: Int) : Screen

    /** [nonce] makes the session ViewModel unique per entry, so each Start gets a fresh pipeline. */
    data class Session(val mode: Mode, val mePersonId: Long?, val nonce: Int) : Screen
    data class Report(
        val result: SessionResult,
        val meta: Map<String, SessionViewModel.SpeakerMeta>,
        /** The finished session's nonce, so the report can reach the same ViewModel for save-retry. */
        val nonce: Int,
    ) : Screen
}

@Composable
fun TalkBalanceApp() {
    MaterialTheme {
        val context = LocalContext.current
        val app = context.applicationContext as TalkBalanceApplication
        val settingsViewModel: SettingsViewModel =
            viewModel(factory = SettingsViewModel.factory(app.settingsRepository))

        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var navNonce by remember { mutableStateOf(0) }
        var showBench by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }

        if (showBench) {
            BenchScreen(onBack = { showBench = false })
            return@MaterialTheme
        }
        if (showSettings) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onDeleteAllData = { app.deleteAllData() },
                onBack = { showSettings = false },
            )
            return@MaterialTheme
        }

        Scaffold(
            topBar = {
                AppBar(onBench = { showBench = true }, onSettings = { showSettings = true })
            },
        ) { padding ->
            val content = Modifier.padding(padding)
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    modifier = content,
                    onAddPerson = { screen = Screen.Enroll(++navNonce) },
                    onStart = { mode, meId -> screen = Screen.Session(mode, meId, ++navNonce) },
                )
                is Screen.Enroll -> EnrollScreen(
                    modifier = content,
                    nonce = s.nonce,
                    onDone = { screen = Screen.Home },
                )
                is Screen.Session -> SessionScreen(
                    modifier = content,
                    mode = s.mode,
                    mePersonId = s.mePersonId,
                    nonce = s.nonce,
                    onFinished = { result, meta -> screen = Screen.Report(result, meta, s.nonce) },
                    onAbort = { screen = Screen.Home },
                )
                is Screen.Report -> ReportScreen(
                    modifier = content,
                    result = s.result,
                    meta = s.meta,
                    nonce = s.nonce,
                    onDone = { screen = Screen.Home },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(onBench: () -> Unit, onSettings: () -> Unit) = TopAppBar(
    title = { Text("Talk Balance") },
    actions = {
        IconButton(
            onClick = onSettings,
            modifier = Modifier.semantics { contentDescription = "Settings" },
        ) { Text("⚙") }
        if (BuildConfig.DEBUG) {
            TextButton(onClick = onBench) { Text("Bench") }
        }
    },
)

// --- Home -----------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    onAddPerson: () -> Unit,
    onStart: (Mode, Long?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TalkBalanceApplication
    val personDao = app.database.personDao()
    val people by personDao.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Mode.TABLE) }
    var meId by remember { mutableStateOf<Long?>(null) }

    // Which row's overflow menu is open, if any.
    var menuForPersonId by remember { mutableStateOf<Long?>(null) }
    // Delete flow: the person pending a confirm dialog.
    var pendingDelete by remember { mutableStateOf<PersonEntity?>(null) }
    // Merge flow: the row "Merge into…" was tapped on, then the target picked for it.
    var mergeSource by remember { mutableStateOf<PersonEntity?>(null) }
    var mergeTarget by remember { mutableStateOf<PersonEntity?>(null) }

    fun cancelMerge() {
        mergeSource = null
        mergeTarget = null
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Talk Balance", style = MaterialTheme.typography.headlineMedium)
        Text("Everything stays on this phone. No audio is ever saved.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == Mode.TABLE, onClick = { mode = Mode.TABLE }, label = { Text("Table") })
            FilterChip(selected = mode == Mode.ME, onClick = { mode = Mode.ME }, label = { Text("Me") })
        }

        Text(
            when (mode) {
                Mode.TABLE -> "Everyone enrolled is tracked; unknown voices are “Everyone else”."
                Mode.ME -> "Pick who you are — you’ll see You vs everyone else."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (people.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("No one enrolled yet. Add a person to start.", Modifier.padding(20.dp))
            }
        } else {
            people.forEach { person ->
                PersonRow(
                    person = person,
                    selectable = mode == Mode.ME,
                    selected = meId == person.id,
                    onSelect = { meId = person.id },
                    menuExpanded = menuForPersonId == person.id,
                    onMenuExpandedChange = { expanded -> menuForPersonId = if (expanded) person.id else null },
                    canMerge = people.size > 1,
                    onDeleteClick = {
                        menuForPersonId = null
                        pendingDelete = person
                    },
                    onMergeClick = {
                        menuForPersonId = null
                        mergeSource = person
                    },
                )
            }
        }

        OutlinedButton(onClick = onAddPerson, modifier = Modifier.fillMaxWidth()) {
            Text("Add person")
        }

        Spacer(Modifier.height(8.dp))

        val canStart = when (mode) {
            Mode.TABLE -> people.isNotEmpty()
            Mode.ME -> meId != null
        }
        Button(
            enabled = canStart,
            onClick = { onStart(mode, if (mode == Mode.ME) meId else null) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
    }

    pendingDelete?.let { person ->
        DeletePersonDialog(
            name = person.name,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    personDao.delete(person)
                    if (meId == person.id) meId = null
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    val source = mergeSource
    if (source != null && mergeTarget == null) {
        MergeTargetPickerDialog(
            sourceName = source.name,
            candidates = people.filter { it.id != source.id },
            onPick = { mergeTarget = it },
            onDismiss = { cancelMerge() },
        )
    }
    val target = mergeTarget
    if (source != null && target != null) {
        MergeConfirmDialog(
            sourceName = source.name,
            targetName = target.name,
            onConfirm = {
                cancelMerge()
                scope.launch {
                    personDao.mergePersons(sourceId = source.id, targetId = target.id)
                    if (meId == source.id) meId = target.id
                }
            },
            onDismiss = { cancelMerge() },
        )
    }
}

@Composable
private fun PersonRow(
    person: PersonEntity,
    selectable: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    canMerge: Boolean,
    onDeleteClick: () -> Unit,
    onMergeClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = selectable) { onSelect() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(colorForIndex(person.colorIndex)))
        Text(person.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (selectable) RadioButton(selected = selected, onClick = onSelect)

        Box {
            IconButton(
                onClick = { onMenuExpandedChange(true) },
                modifier = Modifier.semantics { contentDescription = "More options for ${person.name}" },
            ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                DropdownMenuItem(
                    text = { Text("Merge into…") },
                    enabled = canMerge,
                    onClick = onMergeClick,
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = onDeleteClick,
                )
            }
        }
    }
}

@Composable
private fun DeletePersonDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = {
            Text(
                "Removes $name's voice profile. Past session stats are kept but lose the name.",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MergeTargetPickerDialog(
    sourceName: String,
    candidates: List<PersonEntity>,
    onPick: (PersonEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge $sourceName into…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(colorForIndex(candidate.colorIndex)))
                        Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MergeConfirmDialog(
    sourceName: String,
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge $sourceName into $targetName?") },
        text = {
            Text(
                "Combines $sourceName's voice recordings into $targetName and moves " +
                    "$sourceName's session stats to $targetName.",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Enrollment -----------------------------------------------------------------------------------

@Composable
private fun EnrollScreen(modifier: Modifier = Modifier, nonce: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TalkBalanceApplication
    val vm: EnrollViewModel = viewModel(
        key = "enroll-$nonce",
        factory = EnrollViewModel.factory(app.sherpaEngines, app.database.personDao()),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var colorIndex by remember { mutableStateOf(0) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) { permissionDenied = false; vm.record(name, colorIndex) } else permissionDenied = true
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) vm.record(name, colorIndex) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Add a person", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            enabled = state !is EnrollViewModel.State.Recording && state !is EnrollViewModel.State.Processing,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Pick a colour")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SpeakerPalette.forEachIndexed { index, color ->
                val isSelected = colorIndex == index
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp) // >= 48dp minimum touch target
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { colorIndex = index }
                        .semantics {
                            contentDescription = "Colour ${index + 1} of ${SpeakerPalette.size}"
                            role = Role.RadioButton
                            selected = isSelected
                        },
                )
            }
        }

        when (val s = state) {
            EnrollViewModel.State.Idle -> {
                if (permissionDenied) {
                    Text("Microphone permission is required.", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = name.trim().isNotEmpty(),
                    onClick = { startRecording() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Record ~10 seconds") }
            }
            is EnrollViewModel.State.Recording -> {
                val remaining = ((EnrollViewModel.RECORD_MS - s.elapsedMs) / 1000).coerceAtLeast(0)
                Text("Listening… keep talking ($remaining s)", style = MaterialTheme.typography.titleMedium)
                MicLevel(level = s.level)
                LinearProgressIndicator(
                    progress = { (s.elapsedMs.toFloat() / EnrollViewModel.RECORD_MS).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            EnrollViewModel.State.Processing -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Building voice profile…")
                }
            }
            is EnrollViewModel.State.Success -> {
                val mergedInto = s.mergedIntoName
                if (mergedInto != null) {
                    Text("Added to $mergedInto.", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Added ${s.name}.", style = MaterialTheme.typography.titleMedium)
                    s.warning?.let { warning ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("${warning.message}. They may be confused during a session.")
                                OutlinedButton(
                                    onClick = { vm.addToExistingPerson() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Add to ${warning.name} instead") }
                            }
                        }
                    }
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(
                    onClick = { name = ""; vm.reset() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add another") }
            }
            is EnrollViewModel.State.Failure -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.reset(); startRecording() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }
        }

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun MicLevel(level: Float) {
    val animated by animateFloatAsState(targetValue = level, label = "mic-level")
    Box(
        Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(animated).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

// --- Session --------------------------------------------------------------------------------------

@Composable
private fun SessionScreen(
    modifier: Modifier = Modifier,
    mode: Mode,
    mePersonId: Long?,
    nonce: Int,
    onFinished: (SessionResult, Map<String, SessionViewModel.SpeakerMeta>) -> Unit,
    onAbort: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TalkBalanceApplication
    val vm: SessionViewModel = viewModel(
        key = "session-$nonce",
        factory = SessionViewModel.factory(
            app.sherpaEngines, app.database.personDao(), app.database.sessionDao(), app.settingsRepository,
        ),
    )

    // Keep the screen awake for the whole session.
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val phase by vm.phase.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    // Foreground-only guarantee: leaving this screen while still live (back, Activity recreation)
    // must never leave an invisible microphone running. A normally-finished session already
    // stopped capture, so keep its controller alive for the report's save-retry.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (vm.phase.value !is SessionViewModel.Phase.Finished) vm.abort()
        }
    }

    // Request permission, then start, exactly once. Only RECORD_AUDIO is needed — there is no
    // notification or foreground service in this release.
    var requested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.start(mode, mePersonId) else onAbort()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!requested) {
            requested = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) vm.start(mode, mePersonId) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Hand the finished result up to nav.
    androidx.compose.runtime.LaunchedEffect(phase) {
        (phase as? SessionViewModel.Phase.Finished)?.let { onFinished(it.result, vm.metaBySpeakerId) }
    }

    // UI ticker: the engine only recomputes elapsed when a speech event arrives, so during silence
    // the clock would freeze and then jump. Advance it once a second from a monotonic base instead.
    var tickElapsedMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(phase) {
        if (phase is SessionViewModel.Phase.Running) {
            val base = System.currentTimeMillis() - live.elapsedMs
            while (true) {
                tickElapsedMs = System.currentTimeMillis() - base
                delay(1_000)
            }
        }
    }
    val shownElapsedMs = if (phase is SessionViewModel.Phase.Running) {
        maxOf(tickElapsedMs, live.elapsedMs)
    } else {
        live.elapsedMs
    }

    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val p = phase) {
            is SessionViewModel.Phase.Failed -> {
                Text(p.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
            else -> {
                val leader = live.speakers.firstOrNull()
                Text(
                    "Leading: ${leader?.label ?: "—"} · ${((leader?.share ?: 0.0) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val speaking = live.isSpeechActive
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text(
                        if (speaking) "Someone is speaking" else "Listening…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(shownElapsedMs), style = MaterialTheme.typography.titleMedium)
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (live.speakers.isEmpty()) {
                        Text("Waiting for the first voices…", style = MaterialTheme.typography.bodyMedium)
                    }
                    live.speakers.forEachIndexed { index, share ->
                        val color = if (share.isEveryoneElse) EveryoneElseColor
                        else vm.metaBySpeakerId[share.speakerId]?.let { colorForIndex(it.colorIndex) }
                            ?: EveryoneElseColor
                        val glow = live.isSpeechActive && index == 0 && !share.isEveryoneElse
                        SpeakerBar(share.label, share.share, color, glow)
                    }
                }

                Button(
                    onClick = { vm.stop() },
                    enabled = phase is SessionViewModel.Phase.Running,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun SpeakerBar(label: String, share: Double, color: Color, glow: Boolean) {
    val width by animateFloatAsState(targetValue = share.toFloat().coerceIn(0f, 1f), label = "bar-$label")
    val borderColor by animateColorAsState(
        targetValue = if (glow) color else Color.Transparent, label = "glow-$label",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, fontWeight = if (glow) FontWeight.Bold else FontWeight.Normal)
            Text("${(share * 100).roundToInt()}%")
        }
        Box(
            Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (glow) 3.dp else 0.dp, borderColor, RoundedCornerShape(14.dp)),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(width.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(14.dp)).background(color),
            )
        }
    }
}

// --- Report ---------------------------------------------------------------------------------------

@Composable
private fun ReportScreen(
    modifier: Modifier = Modifier,
    result: SessionResult,
    meta: Map<String, SessionViewModel.SpeakerMeta>,
    nonce: Int,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TalkBalanceApplication
    // Same keyed instance the session used, retained in the Activity store — so its save state is
    // still observable and a failed save can be retried from here.
    val vm: SessionViewModel = viewModel(
        key = "session-$nonce",
        factory = SessionViewModel.factory(
            app.sherpaEngines, app.database.personDao(), app.database.sessionDao(), app.settingsRepository,
        ),
    )
    val persistState by vm.persistState.collectAsStateWithLifecycle()

    val total = result.speakers.sumOf { it.talkMs }.coerceAtLeast(1)
    val rows = result.speakers.sortedByDescending { it.talkMs }
    val everyoneElseLabel = result.mode.everyoneElseLabel
    val balance = balanceTarget(result.mode, meta.size)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Report", style = MaterialTheme.typography.headlineMedium)
        Text("Talked for ${formatDuration(result.durationMs)}")
        Text(balance.copy, style = MaterialTheme.typography.bodySmall)

        when (val ps = persistState) {
            is PersistState.Saved -> Text(
                "Saved to this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            is PersistState.Failed -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${ps.message} It wasn't saved.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { vm.retryPersist() }) { Text("Retry save") }
                }
            }
            PersistState.Saving -> Text(
                "Saving…",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        rows.forEach { stat ->
            val isEE = stat.speakerId == SessionStats.EVERYONE_ELSE
            val label = if (isEE) everyoneElseLabel else meta[stat.speakerId]?.name ?: stat.speakerId
            val color = if (isEE) EveryoneElseColor
            else meta[stat.speakerId]?.let { colorForIndex(it.colorIndex) } ?: EveryoneElseColor
            val share = stat.talkMs.toDouble() / total

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakerBar(label, share, color, glow = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${formatDuration(stat.talkMs)} talk", style = MaterialTheme.typography.bodySmall)
                        Text("${stat.turns} turns", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "longest ${formatDuration(stat.longestMonologueMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
    }
}

// --- helpers --------------------------------------------------------------------------------------

private fun formatDuration(value: Long): String {
    val seconds = (value / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
