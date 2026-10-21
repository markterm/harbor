package com.markwhite.harbor.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.FirmwareEntry
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.ui.LayoutMode
import com.markwhite.harbor.ui.Screen
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.launch

/** The firmware library (ui-spec §6.3): the catalog, what is kept here, and local packages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: HarborApp, ui: UiState, mode: LayoutMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by app.catalog.entries.collectAsState()
    val installed by app.store.installed.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FirmwareEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var byoFor by remember { mutableStateOf<FirmwareEntry?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val forEntry = byoFor
        byoFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readUri(context, uri, 8L shl 20) ?: return@launch run { error = "Couldn't read that file." }
            app.harbor.openPackage(bytes, displayName(context, uri), forEntry).fold(
                { ui.go(Screen.Play) },
                { error = it.message ?: "That file isn't a firmware package Harbor can open." },
            )
        }
    }
    fun openFile(forEntry: FirmwareEntry? = null) {
        byoFor = forEntry
        open.launch(arrayOf("application/octet-stream", "*/*"))
    }
    fun play(e: FirmwareEntry) {
        if (e.byo && app.store.latestFor(e.id) == null) { selected = e; return }
        app.harbor.play(e)
        ui.go(Screen.Play)
    }

    val list = catalog.firmwares.filter {
        query.isBlank() || listOf(it.name, it.author, it.about).any { f -> f.contains(query, ignoreCase = true) }
    }
    val wide = mode == LayoutMode.Expanded

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search firmwares") }, modifier = Modifier.fillMaxWidth())
                else Text("Firmwares")
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search") }
                IconButton(onClick = {
                    refreshing = true
                    scope.launch {
                        app.catalog.refresh().onFailure { error = "Couldn't refresh the list; showing the one from ${if (app.catalog.refreshedAt == 0L) "this app version" else com.markwhite.harbor.ui.timeOf(app.catalog.refreshedAt)}." }
                        refreshing = false
                    }
                }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "Refresh list") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Tokens.room),
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text("${catalog.firmwares.size} firmwares for the FM-1", style = MaterialTheme.typography.bodyMedium, color = Tokens.dim)
                    }
                    if (list.isEmpty()) item { Text("No firmware by that name in the harbor.") }
                    val (checked, hubOnly) = list.partition { !it.hubOnly }
                    items(checked, key = { it.id }) { e ->
                        val kept = installed.filter { it.catalogId == e.id }.maxByOrNull { it.addedAt }
                        FirmwareCard(app, e, kept != null, ui.docked?.entry?.id == e.id, kept?.let { app.settings.clock(it.sha) }, kept?.size,
                            onPlay = { play(e) }, onDetails = { selected = e }, onOpenByo = { openFile(e) })
                    }
                    if (hubOnly.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = 8.dp)) {
                                Section("More from the FM-1 Firmware Hub")
                                Text("Newer firmwares the emulator's authors haven't checked yet. Most run; some may not.",
                                    style = MaterialTheme.typography.bodySmall, color = Tokens.dim, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                            }
                        }
                        items(hubOnly, key = { it.id }) { e ->
                            val kept = installed.filter { it.catalogId == e.id }.maxByOrNull { it.addedAt }
                            FirmwareCard(app, e, kept != null, ui.docked?.entry?.id == e.id, kept?.let { app.settings.clock(it.sha) }, kept?.size,
                                onPlay = { play(e) }, onDetails = { selected = e }, onOpenByo = { openFile(e) })
                        }
                    }
                    val locals = installed.filter { it.catalogId == "local" }
                    if (locals.isNotEmpty()) {
                        item { Section("Opened from files") }
                        items(locals, key = { it.sha }) { pkg ->
                            Card(colors = CardDefaults.cardColors(containerColor = Tokens.card), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pkg.title, style = MaterialTheme.typography.titleMedium)
                                        Text(formatBytes(pkg.size) + " · " + pkg.sha.take(8), style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                                    }
                                    FilledTonalButton(onClick = { app.harbor.boot(pkg); ui.go(Screen.Play) }) { Text("Play") }
                                }
                            }
                        }
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { openFile() },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Open a .fwsc file…") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
            if (wide) {
                Box(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    val e = selected ?: ui.docked?.entry
                    if (e != null) FirmwareDetails(app, e, onPlay = { play(e) }, onOpenByo = { openFile(e) })
                    else Text("Pick a firmware to see its details.", color = Tokens.dim)
                }
            }
        }
    }

    val sel = selected
    if (!wide && sel != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                FirmwareDetails(app, sel, onPlay = { selected = null; play(sel) }, onOpenByo = { selected = null; openFile(sel) })
            }
        }
    }
    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }, text = { Text(msg) })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FirmwareCard(
    app: HarborApp, e: FirmwareEntry, kept: Boolean, docked: Boolean, clock: Int?, size: Long?,
    onPlay: () -> Unit, onDetails: () -> Unit, onOpenByo: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Tokens.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (docked) Tokens.amber.copy(alpha = 0.6f) else Tokens.line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FirmwareImage(app, e.image, null, 160.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Monogram(e.id, e.name)
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(e.author, e.license).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Tokens.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (docked) Text("DOCKED", color = Tokens.amber, style = com.markwhite.harbor.ui.theme.Eyebrow.copy(color = Tokens.amber, fontSize = 12.sp))
            }
            Text(e.about, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kept && size != null) Text("↓ kept · ${formatBytes(size)}", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (clock != null && clock < 240) Text("$clock MHz here", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (e.byo) Text("Bring your own", style = MaterialTheme.typography.labelSmall, color = Tokens.amber)
                if (e.beta) Text("Beta", style = MaterialTheme.typography.labelSmall, color = Tokens.amber)
                if (FirmwareNotes.makesNoSound(e.id)) Text("MIDI controller: no sound of its own", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                when {
                    e.byo && !kept -> {
                        Button(onClick = onOpenByo) { Text("Open FM-1.fwsc…") }
                        e.download?.let { url -> LinkButton("M-VAVE", url) }
                    }
                    e.playable && docked -> FilledTonalButton(onClick = onPlay) { Text("Docked") }
                    e.playable -> Button(onClick = onPlay, contentPadding = PaddingValues(start = 14.dp, end = 20.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text("Play", Modifier.padding(start = 6.dp))
                    }
                    else -> Text("Not available in Harbor yet", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                }
                e.repo?.let { url -> LinkButton("Source", url) }
            }
        }
    }
}

/**
 * A firmware's screenshot at its own shape, no taller than [maxHeight], centred on the dark of a
 * screen when it is narrower than the space. Nothing at all while it loads, or when the Hub has no
 * usable one: the card reads the same without it.
 */
@Composable
private fun FirmwareImage(app: HarborApp, url: String?, description: String?, maxHeight: Dp) {
    if (url == null) return
    val image by produceState(app.screenshots.cached(url), url) { if (value == null) value = app.screenshots.load(url) }
    val img = image ?: return
    // a sliver or a tower would be a broken image, not a screenshot: keep the frame sensible
    val ratio = (img.width.toFloat() / img.height).coerceIn(0.75f, 3f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(minOf(maxWidth / ratio, maxHeight)).clip(RoundedCornerShape(12.dp)).background(Tokens.well),
            contentAlignment = Alignment.Center,
        ) {
            Image(img, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * A firmware's tile: its initial on a colour of its own (from its id, so it never changes), for
 * telling 49 entries apart at a glance.
 */
@Composable
private fun Monogram(id: String, name: String) {
    val hue = ((id.hashCode() % 360) + 360) % 360
    val bg = Color.hsl(hue.toFloat(), 0.35f, 0.24f)
    val fg = Color.hsl(hue.toFloat(), 0.70f, 0.76f)
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = fg, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun FirmwareDetails(app: HarborApp, e: FirmwareEntry, onPlay: () -> Unit, onOpenByo: () -> Unit) {
    val context = LocalContext.current
    val installed by app.store.installed.collectAsState()
    val versions = installed.filter { it.catalogId == e.id }.sortedByDescending { it.addedAt }
    var confirmReset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FirmwareImage(app, e.image, "Screenshot of ${e.name}", 280.dp)
        Text(e.title, style = MaterialTheme.typography.headlineSmall)
        Text("by " + listOf(e.author, e.license).filter { it.isNotBlank() }.joinToString(" · "), color = Tokens.dim)
        if (e.hubOnly) Text("From the FM-1 Firmware Hub; not yet checked in the emulator. Its licence is in its source.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        Text(e.about)
        FirmwareNotes.midiHint(e.id)?.let { Text("MIDI: $it", style = MaterialTheme.typography.bodySmall) }
        if (FirmwareNotes.makesNoSound(e.id)) Text("${e.name} is a MIDI controller: it makes no sound of its own in Harbor.", style = MaterialTheme.typography.bodySmall)
        Text("A shared save includes the firmware, so its licence applies.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        if (e.byo) {
            Text("Harbor can't include M-VAVE's firmware. Download FM-1.fwsc from M-VAVE, then open it here. Harbor keeps it for next time.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.download?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Download from M-VAVE ↗") } }
                FilledTonalButton(onClick = onOpenByo) { Text("Open FM-1.fwsc…") }
            }
        }
        if (e.playable && (!e.byo || versions.isNotEmpty())) FilledTonalButton(onClick = onPlay) { Text("Play") }
        if (e.embedded != null) Text("This firmware lives inside its installer page; Harbor can't fetch it yet.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.repo?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("Source ↗") } }
            e.hub?.let { id -> TextButton(onClick = { openUrl(context, "https://thefirmwarehub.com/#fw-$id") }) { Text("On the Hub ↗") } }
            e.installer?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("Install on a real FM-1 ↗") } }
        }
        if (versions.isNotEmpty()) {
            Section("Kept on this device")
            for (v in versions) {
                val clock = app.settings.clock(v.sha)
                Text("${v.title} · ${formatBytes(v.size)} · ${v.sha.take(8)}" + (if (clock < 240) " · $clock MHz here" else ""), style = MaterialTheme.typography.bodySmall)
            }
            val newest = versions.first()
            // newer on the hub: no kept version has its checksum (or, without one, its URL)
            val current = e.sha256?.let { sha -> versions.any { it.sha.equals(sha, ignoreCase = true) } } ?: (newest.url == null || newest.url == e.`package`)
            if (e.`package` != null && !current) {
                FilledTonalButton(onClick = { app.harbor.play(e, update = true) }) { Text("Update available: download ${e.version}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmReset = true }) { Text("Reset saved flash…") }
                TextButton(onClick = { versions.forEach { app.store.remove(it.sha) } }) { Text("Remove download") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset ${e.name}?") },
            text = { Text("Everything ${e.name} saved to its flash on this device is removed. The firmware itself stays.") },
            confirmButton = { TextButton(onClick = { versions.forEach { app.harbor.resetSave(it) }; confirmReset = false }) { Text("Reset and restart") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
