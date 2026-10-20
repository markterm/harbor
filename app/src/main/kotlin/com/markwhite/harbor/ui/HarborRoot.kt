package com.markwhite.harbor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.BufferMode
import com.markwhite.harbor.engine.Docked
import com.markwhite.harbor.engine.EngineStatus
import com.markwhite.harbor.engine.HarborEvent
import com.markwhite.harbor.engine.Phase
import com.markwhite.harbor.engine.SaveState
import com.markwhite.harbor.midi.HarborMidiService
import com.markwhite.harbor.ui.panel.PanelPrefs
import com.markwhite.harbor.ui.screens.AboutScreen
import com.markwhite.harbor.ui.screens.AudioScreen
import com.markwhite.harbor.ui.screens.ControlsScreen
import com.markwhite.harbor.ui.screens.HelpScreen
import com.markwhite.harbor.ui.screens.LibraryScreen
import com.markwhite.harbor.ui.screens.MidiScreen
import com.markwhite.harbor.ui.screens.Onboarding
import com.markwhite.harbor.ui.screens.SavesScreen
import com.markwhite.harbor.ui.screens.SettingsHub

enum class Screen { Play, Library, Saves, Midi, Audio, Controls, Help, About, Settings }

/** What every screen reads, gathered from the app's flows once per recomposition. */
data class UiState(
    val phase: Phase,
    val docked: Docked?,
    val status: EngineStatus,
    val save: SaveState,
    val midiActivity: Long,
    val midiSources: Int,
    val midiNames: List<String>,
    val usbHint: Boolean,
    val bufferMode: BufferMode,
    val recording: Boolean,
    val go: (Screen) -> Unit,
) {
    val title: String get() = docked?.let { it.entry?.title ?: it.pkg.title } ?: "Nothing docked"
    val shortName: String get() = docked?.let { it.entry?.name ?: it.pkg.name } ?: "Harbor"
}

/** Files opened from outside ("Open with Harbor"): handled once the UI is up. */
class PendingOpen(val uri: android.net.Uri)

/** What the system bars do on the current screen. */
enum class Bars { Shown, NavHidden, Hidden }

@Composable
fun HarborRoot(app: HarborApp, pending: PendingOpen?, onPendingHandled: () -> Unit, onPerformance: (Bars) -> Unit) {
    val harbor = app.harbor
    val s = app.settings
    var onboarded by remember { mutableStateOf(s.onboarded) }
    if (!onboarded) {
        Onboarding(app) {
            s.onboarded = true
            onboarded = true
        }
        return
    }

    val stack = remember { mutableStateListOf(Screen.Play) }
    val current = stack.last()
    fun go(screen: Screen) {
        if (screen == Screen.Play) { stack.clear(); stack.add(Screen.Play) }
        else if (current != screen) stack.add(screen)
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    LaunchedEffect(Unit) {
        harbor.ensureStarted()
        harbor.resumeLast()
    }

    val phase by harbor.phase.collectAsState()
    val docked by harbor.docked.collectAsState()
    val status by harbor.status.collectAsState()
    val save by harbor.save.collectAsState()
    val activity by app.midi.activity.collectAsState()
    val sources by app.midi.sources.collectAsState()
    val usbHint by app.midi.usbHint.collectAsState()
    val buffer by s.bufferMode.collectAsState()
    val clients by HarborMidiService.clients.collectAsState()
    val recording by harbor.recording.collectAsState()
    val connected = sources.filter { it.enabled && it.connected }
    val ui = UiState(phase, docked, status, save, activity, connected.size + clients, connected.map { it.name } + List(clients) { "An app on this phone" },
        usbHint, buffer, recording != null, ::go)

    val prefs = PanelPrefs(
        knobGesture = s.knobGesture.collectAsState().value,
        dpPerDetent = s.knobDpPerDetent.collectAsState().value,
        tapToStep = s.tapToStep.collectAsState().value,
        hapticKnobs = s.hapticKnobs.collectAsState().value,
        hapticButtons = s.hapticButtons.collectAsState().value,
        hapticKeys = s.hapticKeys.collectAsState().value,
        holdAssist = s.holdAssist.collectAsState().value,
        noteNames = s.noteNames.collectAsState().value,
        smoothLcd = s.smoothLcd.collectAsState().value,
        flat = s.flatPanel.collectAsState().value,
        finish = com.markwhite.harbor.ui.panel.finishOf(s.finish.collectAsState().value),
    )
    val leds = com.markwhite.harbor.ui.panel.rememberLeds(harbor)
    val master by harbor.master.collectAsState()
    val keybed by s.keybedMode.collectAsState()
    // kept across recompositions so the panel's controls can skip when nothing they show changed
    val panel = remember(harbor, prefs, leds, master, keybed) { PanelState(harbor, prefs, leds, master, keybed) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        harbor.events.collect { e ->
            when (e) {
                is HarborEvent.ClockReduced -> {
                    val r = snackbar.showSnackbar("Running ${e.name} at ${e.mhz} MHz so it plays smoothly on this phone.", actionLabel = "Details",
                        withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) go(Screen.Audio)
                }
                is HarborEvent.Message -> snackbar.showSnackbar(e.text)
                is HarborEvent.Recorded -> {
                    val r = snackbar.showSnackbar("Saved “${e.name}” to ${com.markwhite.harbor.data.Recordings.FOLDER_NAME}.", actionLabel = "Share",
                        withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) shareRecording(context, e.uri, e.name)
                }
            }
        }
    }

    var performance by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val mode = layoutModeOf(config.screenWidthDp.dp, config.screenHeightDp.dp)
    val immersive by s.immersive.collectAsState()
    // performance mode, and phone landscape on the Play screen, hide the system bars; elsewhere the
    // Play screen hides only the navigation bar, which lifts Android's 200 dp cap on gesture
    // exclusion so the whole panel can keep edge swipes from turning into Back
    val hideBars = performance || (mode == LayoutMode.CompactLandscape && immersive && current == Screen.Play)
    val bars = when {
        hideBars -> Bars.Hidden
        current == Screen.Play -> Bars.NavHidden
        else -> Bars.Shown
    }
    LaunchedEffect(bars) { onPerformance(bars) }
    // tablet landscape with the pane hidden is for playing: big keys, no rail (the menu still goes everywhere)
    val bigKeys = mode == LayoutMode.Expanded && current == Screen.Play && !s.tabletPane.collectAsState().value
    val rail = (mode == LayoutMode.Medium || mode == LayoutMode.Expanded) && !performance && !bigKeys

    Scaffold(snackbarHost = {
        SnackbarHost(snackbar) { data ->
            androidx.compose.material3.Snackbar(
                data, containerColor = androidx.compose.ui.graphics.Color(0xFF2A2B2F), contentColor = com.markwhite.harbor.ui.theme.Tokens.ink,
                actionColor = com.markwhite.harbor.ui.theme.Tokens.amber, dismissActionContentColor = com.markwhite.harbor.ui.theme.Tokens.dim, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            )
        }
    }, containerColor = if (current == Screen.Play && (mode == LayoutMode.CompactPortrait || mode == LayoutMode.CompactLandscape)) prefs.finish.bodyTop else com.markwhite.harbor.ui.theme.Tokens.room, contentWindowInsets = WindowInsets(0)) { pad ->
        Row(Modifier.fillMaxSize().padding(pad).let { if (hideBars) it.displayCutoutPadding() else it.safeDrawingPadding() }) {
            if (rail) {
                HarborRail(
                    when (current) {
                        Screen.Play -> Dest.Play
                        Screen.Library -> Dest.Library
                        Screen.Saves -> Dest.Saves
                        Screen.Midi -> Dest.Midi
                        else -> Dest.Settings
                    },
                ) { d ->
                    go(when (d) {
                        Dest.Play -> Screen.Play
                        Dest.Library -> Screen.Library
                        Dest.Saves -> Screen.Saves
                        Dest.Midi -> Screen.Midi
                        Dest.Settings -> Screen.Settings
                    })
                }
            }
            Box(Modifier.weight(1f)) {
                when (current) {
                    Screen.Play -> PlayScreen(ui, panel, mode, ::go, performance) { performance = it }
                    Screen.Library -> LibraryScreen(app, ui, mode, ::back)
                    Screen.Saves -> SavesScreen(app, ui, ::back)
                    Screen.Midi -> MidiScreen(app, ui, ::back)
                    Screen.Audio -> AudioScreen(app, ui, ::back)
                    Screen.Controls -> ControlsScreen(app, ::back)
                    Screen.Help -> HelpScreen(::back)
                    Screen.About -> AboutScreen(app, ::back)
                    Screen.Settings -> SettingsHub(::go, ::back)
                }
            }
        }
    }

    // Bluetooth output: warn once per device about its delay (ui-spec §10.2)
    val btWarning by s.btWarning.collectAsState()
    var btDevice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status.deviceId, btWarning) {
        val name = com.markwhite.harbor.ui.screens.bluetoothRoute(context, status.deviceId)
        btDevice = if (btWarning && name != null && !s.btWarned(name)) name else null
    }
    btDevice?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { s.setBtWarned(name); btDevice = null },
            text = { androidx.compose.material3.Text("Bluetooth headphones add about 150–300 ms of delay to what you play on screen. " +
                "For playing, use the speaker, wired or USB audio. Bluetooth is fine for listening.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { s.setBtWarned(name); btDevice = null }) { androidx.compose.material3.Text("OK") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { s.setBtWarning(false); btDevice = null }) { androidx.compose.material3.Text("Don't show again") } },
        )
    }

    if (pending != null) {
        LaunchedEffect(pending) {
            openExternal(app, pending.uri, snackbar)
            onPendingHandled()
        }
    }
}

/** A `.fwsc` or `.fm1save` opened from another app. */
/** The system share sheet for a recording (the receiving app is granted read access to it). */
private fun shareRecording(context: android.content.Context, uri: android.net.Uri, name: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("audio/x-wav")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_TITLE, "$name.wav")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // the share sheet's preview names the file from the clip (else it shows the MediaStore id)
    send.clipData = android.content.ClipData.newUri(context.contentResolver, "$name.wav", uri)
    runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
}

private suspend fun openExternal(app: HarborApp, uri: android.net.Uri, snackbar: SnackbarHostState) {
    val bytes = runCatching {
        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return run { snackbar.showSnackbar("Couldn't read that file.") }
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware"
    if (bytes.firstOrNull() == '{'.code.toByte()) {
        runCatching { app.harbor.readSave(bytes) }.fold(
            { summary ->
                app.harbor.applySave(summary)
                snackbar.showSnackbar("Restored ${summary.name} (${summary.sectors} flash sectors).")
            },
            { snackbar.showSnackbar("That save couldn't be imported: ${it.message}") },
        )
    } else {
        app.harbor.openPackage(bytes, name).onFailure { snackbar.showSnackbar(it.message ?: "That file isn't a firmware package.") }
    }
}
