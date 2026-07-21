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
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    var mode by remember { mutableStateOf(Mode.DISCOVER) }
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
            FilterChip(
                selected = mode == Mode.DISCOVER,
                onClick = { mode = Mode.DISCOVER },
                label = { Text("Discover") },
            )
            FilterChip(selected = mode == Mode.TABLE, onClick = { mode = Mode.TABLE }, label = { Text("Table") })
            FilterChip(selected = mode == Mode.ME, onClick = { mode = Mode.ME }, label = { Text("Me") })
        }

        Text(
            when (mode) {
                Mode.DISCOVER ->
                    "Start and discover — no setup. Voices are found live and labelled Speaker 1, 2, 3… You can rename them any time."
                Mode.TABLE -> "Everyone enrolled is tracked; unknown voices are “Everyone else”."
                Mode.ME -> "Pick who you are — you’ll see You vs everyone else."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        // Enrollment (people list + add) only applies to the setup-based modes. Discover needs none.
        if (mode != Mode.DISCOVER) {
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
        }

        Spacer(Modifier.height(8.dp))

        val canStart = when (mode) {
            Mode.DISCOVER -> true // no enrollment required
            Mode.TABLE -> people.isNotEmpty()
            Mode.ME -> meId != null
        }
        Button(
            enabled = canStart,
            onClick = { onStart(mode, if (mode == Mode.ME) meId else null) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                if (mode == Mode.DISCOVER) "Start and discover" else "Start",
                style = MaterialTheme.typography.titleMedium,
            )
        }
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

    // Foreground-only guarantee (see [abortOnStopObserver]): leaving the app mid-enrollment (Home,
    // Recents/app-switch, screen lock) reaches ON_STOP; Back/opening Settings disposes the screen.
    // Both must abort the still-running record + processing so the mic is released and — critically —
    // no PersonEntity is inserted after the user has left (which would race Delete-all).
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = abortOnStopObserver { vm.abort() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.abort()
        }
    }

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

    // Keep the screen awake for the session only when the user has asked us to (Settings →
    // "Keep screen on"). Honour the live setting rather than hard-forcing it on.
    val view = LocalView.current
    val keepScreenOn by app.settingsRepository.keepScreenOn.collectAsStateWithLifecycle(initialValue = true)
    androidx.compose.runtime.DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val phase by vm.phase.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val displayMeta by vm.displayMeta.collectAsStateWithLifecycle()

    // Discover mode: which speaker's rename dialog is open (speakerId to its current label), if any.
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Foreground-only guarantee (this release has no foreground service — see [abortOnStopObserver]).
    // The real "app is no longer in the foreground" signal is the Activity lifecycle reaching
    // ON_STOP (Home, Recents/app-switch, screen lock, Back/finish); composable disposal alone does
    // NOT fire on Home/app-switch, so we must observe the lifecycle to release the microphone.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = abortOnStopObserver {
            if (vm.phase.value !is SessionViewModel.Phase.Finished) vm.abort()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Also abort on ordinary disposal (Back / Activity recreation) unless the session
            // finished normally (its controller is kept alive for the report's save-retry).
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
                val leaderLabel = leader?.let { displayMeta[it.speakerId]?.name ?: it.label } ?: "—"
                Text(
                    "Leading: $leaderLabel · ${((leader?.share ?: 0.0) * 100).roundToInt()}%",
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

                // Live timeline ribbon: the recent window of finished segments, one lane per speaker.
                if (live.segments.isNotEmpty()) {
                    TimelineRibbon(
                        segments = live.segments,
                        elapsedMs = shownElapsedMs,
                        colorFor = { id -> displayMeta[id]?.let { colorForIndex(it.colorIndex) } ?: EveryoneElseColor },
                        laneOrder = live.speakers.map { it.speakerId },
                    )
                }

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (live.speakers.isEmpty()) {
                        Text("Waiting for the first voices…", style = MaterialTheme.typography.bodyMedium)
                    }
                    live.speakers.forEachIndexed { index, share ->
                        val meta = displayMeta[share.speakerId]
                        val label = meta?.name ?: share.label
                        val color = if (share.isEveryoneElse) EveryoneElseColor
                        else meta?.let { colorForIndex(it.colorIndex) } ?: EveryoneElseColor
                        val glow = live.isSpeechActive && index == 0 && !share.isEveryoneElse
                        // Discover speakers are renameable by tapping the row.
                        val renameable = mode == Mode.DISCOVER && !share.isEveryoneElse
                        SpeakerBar(
                            label = label,
                            share = share.share,
                            color = color,
                            glow = glow,
                            onRename = if (renameable) {
                                { renameTarget = share.speakerId to label }
                            } else null,
                        )
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

    renameTarget?.let { (speakerId, current) ->
        RenameSpeakerDialog(
            currentLabel = current,
            onConfirm = { newName ->
                vm.rename(speakerId, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun SpeakerBar(
    label: String,
    share: Double,
    color: Color,
    glow: Boolean,
    onRename: (() -> Unit)? = null,
) {
    val width by animateFloatAsState(targetValue = share.toFloat().coerceIn(0f, 1f), label = "bar-$label")
    val borderColor by animateColorAsState(
        targetValue = if (glow) color else Color.Transparent, label = "glow-$label",
    )
    // A renameable row is a >=48dp tap target that opens the rename dialog.
    val rowModifier = if (onRename != null) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onRename)
            .semantics { contentDescription = "Rename $label" }
            .padding(vertical = 4.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, fontWeight = if (glow) FontWeight.Bold else FontWeight.Normal)
                if (onRename != null) Text("✎", style = MaterialTheme.typography.bodySmall)
            }
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

/**
 * Live timeline ribbon: the recent [windowMs] window of finished speech [segments], each drawn as a
 * block in its speaker's colour on a per-speaker lane. Newer segments are to the right (now = right
 * edge). Pure Compose [Canvas], no chart libraries.
 */
@Composable
private fun TimelineRibbon(
    segments: List<uk.co.riera.talkbalance.session.TalkSegment>,
    elapsedMs: Long,
    colorFor: (String) -> Color,
    laneOrder: List<String>,
    windowMs: Long = 60_000L,
) {
    // Lanes: keep a stable order (share-sorted speakers), append any segment speakers not yet listed.
    val lanes = remember(laneOrder, segments) {
        val extras = segments.map { it.speakerId }.distinct().filter { it !in laneOrder }
        (laneOrder + extras).ifEmpty { listOf("") }
    }
    val laneHeight = 14.dp
    val laneGap = 4.dp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Last 60 seconds", style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(laneHeight * lanes.size + laneGap * (lanes.size - 1).coerceAtLeast(0))
                .semantics { contentDescription = "Timeline of who spoke in the last minute" },
        ) {
            val windowEnd = maxOf(elapsedMs, windowMs)
            val windowStart = windowEnd - windowMs
            val laneH = laneHeight.toPx()
            val gapH = laneGap.toPx()
            lanes.forEachIndexed { index, speakerId ->
                val top = index * (laneH + gapH)
                // Lane background track.
                drawRoundRect(
                    color = trackColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, laneH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                )
                segments.asSequence()
                    .filter { it.speakerId == speakerId && it.endMs >= windowStart }
                    .forEach { seg ->
                        val s = ((seg.startMs - windowStart).toFloat() / windowMs).coerceIn(0f, 1f)
                        val e = ((seg.endMs - windowStart).toFloat() / windowMs).coerceIn(0f, 1f)
                        val x = s * size.width
                        val w = ((e - s) * size.width).coerceAtLeast(2f)
                        drawRoundRect(
                            color = colorFor(speakerId),
                            topLeft = androidx.compose.ui.geometry.Offset(x, top),
                            size = androidx.compose.ui.geometry.Size(w, laneH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                        )
                    }
            }
        }
    }
}

@Composable
private fun RenameSpeakerDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename speaker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Give “$currentLabel” a name — e.g. Dad, Sam.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New speaker name" },
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    // Reactive meta so a rename on this screen repaints the donut, timeline and rows immediately.
    // For enrolled modes this equals the passed-in [meta]; for Discover it tracks renames.
    val liveMeta by vm.displayMeta.collectAsStateWithLifecycle()
    val displayMeta = liveMeta.ifEmpty { meta }

    val isDiscover = result.mode == Mode.DISCOVER
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val total = result.speakers.sumOf { it.talkMs }.coerceAtLeast(1)
    val rows = result.speakers.sortedByDescending { it.talkMs }
    val everyoneElseLabel = result.mode.everyoneElseLabel
    val balance = balanceTarget(result.mode, displayMeta.size.coerceAtLeast(1))

    fun labelFor(speakerId: String, isEE: Boolean): String =
        if (isEE) everyoneElseLabel else displayMeta[speakerId]?.name ?: speakerId

    fun colorFor(speakerId: String, isEE: Boolean): Color =
        if (isEE) EveryoneElseColor
        else displayMeta[speakerId]?.let { colorForIndex(it.colorIndex) } ?: EveryoneElseColor

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

        // Talk-share donut.
        TalkShareDonut(
            slices = rows.map { stat ->
                val isEE = stat.speakerId == SessionStats.EVERYONE_ELSE
                DonutSlice(labelFor(stat.speakerId, isEE), stat.talkMs, colorFor(stat.speakerId, isEE))
            },
        )

        // Full-session timeline of every attributed segment.
        if (result.segments.isNotEmpty()) {
            SessionTimeline(
                segments = result.segments,
                durationMs = result.durationMs.coerceAtLeast(1),
                laneOrder = rows.map { it.speakerId },
                colorFor = { id -> colorFor(id, id == SessionStats.EVERYONE_ELSE) },
            )
        }

        rows.forEach { stat ->
            val isEE = stat.speakerId == SessionStats.EVERYONE_ELSE
            val label = labelFor(stat.speakerId, isEE)
            val color = colorFor(stat.speakerId, isEE)
            val share = stat.talkMs.toDouble() / total
            val renameable = isDiscover && !isEE

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakerBar(
                        label = label,
                        share = share,
                        color = color,
                        glow = false,
                        onRename = if (renameable) {
                            { renameTarget = stat.speakerId to label }
                        } else null,
                    )
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

    renameTarget?.let { (speakerId, current) ->
        RenameSpeakerDialog(
            currentLabel = current,
            onConfirm = { newName ->
                vm.rename(speakerId, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** One wedge of the talk-share donut. */
private data class DonutSlice(val label: String, val talkMs: Long, val color: Color)

/**
 * Talk-share donut (pie with a hole): each speaker a wedge sized by their share of total talk time,
 * in their colour, with a percentage legend. Pure Compose [Canvas].
 */
@Composable
private fun TalkShareDonut(slices: List<DonutSlice>) {
    val total = slices.sumOf { it.talkMs }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(120.dp)
                .semantics { contentDescription = "Talk-share donut chart" },
        ) {
            var startAngle = -90f
            val stroke = size.minDimension * 0.22f
            val inset = stroke / 2
            slices.forEach { slice ->
                val sweep = (slice.talkMs.toFloat() / total) * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                startAngle += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            slices.forEach { slice ->
                val pct = (slice.talkMs.toDouble() / total * 100).roundToInt()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(slice.color))
                    Text("${slice.label} · $pct%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Full-session timeline: every attributed [segments] segment drawn as a block in its speaker's
 * colour on a per-speaker lane, scaled to the whole session [durationMs]. Pure Compose [Canvas].
 */
@Composable
private fun SessionTimeline(
    segments: List<uk.co.riera.talkbalance.session.TalkSegment>,
    durationMs: Long,
    laneOrder: List<String>,
    colorFor: (String) -> Color,
) {
    val lanes = remember(laneOrder, segments) {
        val extras = segments.map { it.speakerId }.distinct().filter { it !in laneOrder }
        (laneOrder + extras).ifEmpty { listOf("") }
    }
    val laneHeight = 16.dp
    val laneGap = 4.dp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val first = segments.firstOrNull()?.startMs ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Session timeline", style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(laneHeight * lanes.size + laneGap * (lanes.size - 1).coerceAtLeast(0))
                .semantics { contentDescription = "Timeline of who spoke across the whole session" },
        ) {
            val laneH = laneHeight.toPx()
            val gapH = laneGap.toPx()
            lanes.forEachIndexed { index, speakerId ->
                val top = index * (laneH + gapH)
                drawRoundRect(
                    color = trackColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, laneH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                )
                segments.asSequence()
                    .filter { it.speakerId == speakerId }
                    .forEach { seg ->
                        val s = ((seg.startMs - first).toFloat() / durationMs).coerceIn(0f, 1f)
                        val e = ((seg.endMs - first).toFloat() / durationMs).coerceIn(0f, 1f)
                        val x = s * size.width
                        val w = ((e - s) * size.width).coerceAtLeast(2f)
                        drawRoundRect(
                            color = colorFor(speakerId),
                            topLeft = androidx.compose.ui.geometry.Offset(x, top),
                            size = androidx.compose.ui.geometry.Size(w, laneH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(laneH / 2),
                        )
                    }
            }
        }
    }
}

// --- helpers --------------------------------------------------------------------------------------

private fun formatDuration(value: Long): String {
    val seconds = (value / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
