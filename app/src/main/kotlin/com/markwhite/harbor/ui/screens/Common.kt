package com.markwhite.harbor.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.ui.theme.Eyebrow
import com.markwhite.harbor.ui.theme.Tokens

/** A destination with a top bar and Up, its content centred at a readable width on tablets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Tokens.room),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A section's label; with [content], the section's rows in a card under it. */
@Composable
fun Section(title: String, content: (@Composable ColumnScope.() -> Unit)? = null) {
    Text(title.uppercase(), style = Eyebrow, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
    if (content != null) InfoCard(content = content)
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Tokens.card),
        border = BorderStroke(1.dp, Tokens.line.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Amber track, light thumb: Material's default puts a dark thumb on the amber. */
@Composable
fun HarborSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked, onCheckedChange = onChange, modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Tokens.amber, checkedBorderColor = Tokens.amber,
            uncheckedThumbColor = Tokens.dim, uncheckedTrackColor = Tokens.well, uncheckedBorderColor = Tokens.line,
        ),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        }
        // the whole row toggles (one target for touch and TalkBack), so the switch only shows it
        HarborSwitch(checked, onChange = null)
    }
}

/** A labelled one-of-n choice. */
@Composable
fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Segmented(options, value, onChange)
    }
}

/** A segmented control: equal segments in a well, one line each, the choice lit amber. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Tokens.well)
            .border(1.dp, Tokens.line.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((v, text) in options) {
            val on = v == value
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                    .background(if (on) Tokens.amber.copy(alpha = 0.18f) else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, Tokens.amber.copy(alpha = 0.7f), RoundedCornerShape(11.dp)) else Modifier)
                    .selectable(on, role = Role.RadioButton) { onChange(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    color = if (on) Tokens.amberHi else Tokens.ink, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * Turning on background play: on Android 13+ the playback notification needs permission, asked
 * for here, when it matters. Harbor plays on either way; refused, the notification just stays out
 * of the shade.
 */
@Composable
fun rememberBackgroundToggle(settings: com.markwhite.harbor.data.Settings): (Boolean) -> Unit {
    val context = LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    return { on ->
        settings.setKeepPlayingInBackground(on)
        if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** A link out of the app: one line, with its arrow. */
@Composable
fun LinkButton(text: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = { openUrl(context, url) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, maxLines = 1, softWrap = false)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 4.dp).size(14.dp).rotate(-45f))
    }
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun readUri(context: Context, uri: Uri, max: Long = 16L shl 20): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}.getOrNull()

fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "file"

fun formatBytes(n: Long): String = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}
