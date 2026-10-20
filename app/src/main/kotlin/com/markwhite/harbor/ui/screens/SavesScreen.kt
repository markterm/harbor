package com.markwhite.harbor.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.engine.Harbor
import com.markwhite.harbor.ui.Screen
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.saveText
import com.markwhite.harbor.ui.theme.Mono
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saves & backup for the docked firmware (ui-spec §6.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavesScreen(app: HarborApp, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val harbor = app.harbor
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<Harbor.ImportSummary?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val installed by app.store.installed.collectAsState()

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            message = if (ok) "Backup saved." else "Couldn't write the backup there."
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readUri(context, uri) ?: return@launch run { message = "Couldn't read that file." }
            runCatching { harbor.readSave(bytes) }.fold({ importing = it }, { message = "That save couldn't be imported: ${it.message}" })
        }
    }
    val syxFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = readUri(context, uri, 64 * 1024) ?: return@rememberLauncherForActivityResult run { message = "Couldn't read that file." }
        if (bytes.size != 4104 || bytes[0] != 0xF0.toByte()) {
            message = "That isn't a 32-voice DX7 bank (.syx, 4104 bytes)."
        } else {
            harbor.loadSyx(bytes)
            message = "Loading ${displayName(context, uri)} into slots 1–32…"
        }
    }

    SubScreen("Saves & backup", onBack) {
        val d = ui.docked
        if (d == null) {
            Text("Nothing is docked. Saves belong to a firmware: pick one first.")
            FilledTonalButton(onClick = { ui.go(Screen.Library) }) { Text("Browse firmwares") }
            return@SubScreen
        }
        InfoCard {
            Text(d.pkg.title, style = MaterialTheme.typography.titleMedium)
            Text("package ${d.pkg.sha.take(8)}…", style = Mono, color = Tokens.dim)
            Text(saveText(ui.save))
            FilledTonalButton(onClick = {
                scope.launch { message = if (harbor.flushSave()) "Saved." else "Nothing new to save (or a flash write is still running)." }
            }) { Text("Save now") }
        }
        Text("ⓘ Patch edits live in the firmware's memory until you use its own SAVE. Everything it saves, Harbor keeps.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.dim)

        Section("Backup") {
            Text("A .fm1save carries this firmware and everything it saved, for another phone or the desktop emulator.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        runCatching { harbor.exportSave() }.fold({ (name, bytes) -> pendingExport = bytes; exportTo.launch(name) },
                            { message = "Couldn't export: ${it.message}" })
                    }
                }) { Text("Export .fm1save…") }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val (name, bytes) = harbor.exportSave()
                            val dir = File(context.cacheDir, "share").apply { mkdirs() }
                            val f = File(dir, name).apply { writeBytes(bytes) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share backup"))
                        }.onFailure { message = "Couldn't share: ${it.message}" }
                    }
                }) { Text("Share…") }
                OutlinedButton(onClick = { importFrom.launch(arrayOf("application/octet-stream", "application/json", "*/*")) }) { Text("Import .fm1save…") }
            }
            if (d.entry?.byo == true) {
                Text("This backup leaves out M-VAVE's firmware: whoever imports it needs their own FM-1.fwsc.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            }
        }

        if (d.entry?.id == "mvave") {
            Section("Stock firmware only") {
                OutlinedButton(onClick = { syxFrom.launch(arrayOf("*/*")) }) { Text("Load DX7 bank (.syx)…") }
                Text("A 32-voice bank goes into slots 1–32 and is kept in this save.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            }
        }

        val versions = installed.filter { it.catalogId == d.pkg.catalogId && d.pkg.catalogId != "local" }.sortedByDescending { it.addedAt }
        if (versions.size > 1) {
            Section("Versions of this firmware") {
                for (v in versions) {
                    val current = v.sha == d.pkg.sha
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text((if (current) "● " else "○ ") + "${v.title} (${v.sha.take(4)}…)" + if (current) " current" else "")
                        if (!current) TextButton(onClick = { harbor.boot(v) }) { Text("Open") }
                    }
                }
                Text("Each version has its own save; Harbor never moves a save between versions.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            }
        }

        Section("Danger zone") {
            Text("Forget everything this firmware saved on this phone and start it as installed.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            OutlinedButton(onClick = { confirmReset = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.error.copy(alpha = 0.5f))) { Text("Reset saved flash…") }
        }
    }

    importing?.let { s ->
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Replace the save?") },
            text = { Text("${s.name}\n${s.sectors} flash sectors\n\nThis replaces what ${s.known?.title ?: "this firmware"} has saved on this device, then restarts it.") },
            confirmButton = { TextButton(onClick = { importing = null; scope.launch { harbor.applySave(s); message = "Restored ${s.name}." } }) { Text("Replace current save") } },
            dismissButton = { TextButton(onClick = { importing = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        val d = ui.docked
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset ${d?.pkg?.name}?") },
            text = { Text("Everything it saved to flash on this device is removed and it restarts as installed.") },
            confirmButton = { TextButton(onClick = { d?.let { harbor.resetSave(it.pkg) }; confirmReset = false }) { Text("Reset and restart") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, text = { Text(m) })
    }
}
