package com.markwhite.harbor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.engine.EngineStatus
import com.markwhite.harbor.engine.SaveState
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The MIDI 5-pin DIN glyph (ui-spec §9.4). */
val DinIcon: ImageVector by lazy {
    ImageVector.Builder("din", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, fill = null) {
            moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, -0.01f, 0f); close()
        }
        for ((x, y) in listOf(6.5f to 12f, 17.5f to 12f, 8f to 8f, 16f to 8f, 12f to 6.5f)) {
            path(fill = SolidColor(Color.Black)) {
                moveTo(x - 1.4f, y); arcToRelative(1.4f, 1.4f, 0f, true, true, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, true, -2.8f, 0f); close()
            }
        }
        path(fill = SolidColor(Color.Black)) { moveTo(10.5f, 16f); lineTo(13.5f, 16f); lineTo(13.5f, 18f); lineTo(10.5f, 18f); close() }
    }.build()
}

@Composable
fun SaveChip(state: SaveState, onClick: () -> Unit) {
    val (icon, text, tint) = when (state) {
        is SaveState.Saved -> Triple(Icons.Filled.CheckCircle, "Saved", Tokens.green)
        SaveState.Saving -> Triple(Icons.Filled.Refresh, "Saving…", Tokens.dim)
        is SaveState.Failed -> Triple(Icons.Filled.Warning, "Save failed", Tokens.amber)
        SaveState.NotSaved -> Triple(null, "Not saved yet", Tokens.dim)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(AssistChipDefaults.IconSize), tint = tint) } },
    )
}

/** DIN glyph, an activity dot that flashes 120 ms per message, and the count of active sources. */
@Composable
fun MidiChip(activity: Long, sources: Int, hint: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (activity != 0L) { lit = true; delay(120); lit = false }
    }
    AssistChip(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "MIDI, $sources sources" + if (hint) ", USB hint" else "" },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(7.dp)) {
                    drawCircle(if (lit) Tokens.amber else Tokens.line)
                }
                if (!compact) Text(if (sources > 0) "MIDI $sources" else "MIDI")
                else if (sources > 0) Text("$sources")
                if (hint) Canvas(Modifier.size(6.dp)) { drawCircle(Tokens.amber, style = Stroke(1.5.dp.toPx())) }
            }
        },
        leadingIcon = { Icon(DinIcon, null, Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/**
 * "240 MHz · 42%": amber over 80% of the audio time, red while dropping out. [compact] (a phone
 * in portrait): one of the two.
 */
@Composable
fun LoadChip(s: EngineStatus, compact: Boolean = false, onClick: () -> Unit) {
    val load = (s.load * 100).toInt()
    val color = when {
        s.late > 0 -> Tokens.error
        s.load > 0.8 -> Tokens.amber
        else -> Tokens.ink
    }
    val text = when {
        s.speed <= 0 -> "${s.mhz} MHz"
        // a phone in portrait: the load, or the clock when it's reduced (the colour still warns)
        compact -> if (s.mhz >= 240) "$load%" else "${s.mhz} MHz"
        else -> "${s.mhz} MHz · $load%"
    }
    AssistChip(onClick = onClick, label = { Text(text, color = color) })
}

enum class Dest(val label: String) { Play("Play"), Library("Library"), Saves("Saves"), Midi("MIDI"), Settings("Settings") }

@Composable
fun HarborRail(current: Dest, go: (Dest) -> Unit) {
    NavigationRail(containerColor = Tokens.bar) {
        Spacer(Modifier.width(8.dp))
        for (d in Dest.entries) {
            NavigationRailItem(
                selected = d == current,
                onClick = { go(d) },
                icon = {
                    Icon(
                        when (d) {
                            Dest.Play -> Icons.Filled.PlayArrow
                            Dest.Library -> Icons.AutoMirrored.Filled.List
                            Dest.Saves -> Icons.Filled.CheckCircle
                            Dest.Midi -> DinIcon
                            Dest.Settings -> Icons.Filled.Settings
                        },
                        null,
                    )
                },
                label = { Text(d.label) },
            )
        }
    }
}

/**
 * Record: a red dot in a ring. While recording, a red pill with the time so far and a stop
 * square; tapping it stops. [frames]: frames recorded (44.1 kHz).
 */
@Composable
fun RecordButton(recording: Boolean, frames: Long, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (!recording) {
        IconButton(onClick = onStart, enabled = enabled, modifier = Modifier.size(40.dp)) {
            val ring = if (enabled) Tokens.ink else Tokens.faint
            val dot = if (enabled) Tokens.rec else Tokens.faint
            Canvas(Modifier.size(20.dp).semantics { contentDescription = "Record" }) {
                drawCircle(ring, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.6.dp.toPx()))
                drawCircle(dot, radius = size.minDimension * 0.27f)
            }
        }
        return
    }
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "rec dot",
    )
    val time = recordTime(frames)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp)
            .clip(shape)
            .background(Tokens.rec.copy(alpha = 0.16f))
            .border(1.dp, Tokens.rec.copy(alpha = 0.6f), shape)
            .clickable(onClickLabel = "Stop recording", onClick = onStop)
            .semantics(mergeDescendants = true) { contentDescription = "Recording, $time. Stop recording" }
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // the dot fades on its own layer: nothing else redraws for it
        Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.background(Tokens.rec, CircleShape))
        Text(time, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = Tokens.ink)
        Box(Modifier.size(9.dp).background(Tokens.ink, RoundedCornerShape(2.dp)))
    }
}

/** "0:42", "12:05", "1:02:09" */
fun recordTime(frames: Long): String {
    val s = frames.coerceAtLeast(0) / 44_100
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun timeOf(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** The performance banner's condition (ui-spec §10.3), from a second-by-second status stream. */
class PerfWatch {
    private var lateSeconds = 0
    private var lastStarved = -1L
    private var lastSecond = 0L

    enum class Problem { None, CantKeepUp, Buffer, Device }

    fun update(s: EngineStatus, lowLatency: Boolean, nowMs: Long): Problem {
        if (nowMs - lastSecond < 1000) return current
        lastSecond = nowMs
        val dropped = lastStarved >= 0 && s.starved > lastStarved
        lastStarved = s.starved
        lateSeconds = if (dropped) lateSeconds + 1 else 0
        current = when {
            lateSeconds < 5 -> Problem.None
            s.load > 0.95 && (s.mhz <= 120 || !s.autoClock) -> Problem.CantKeepUp
            s.load > 0.95 -> Problem.None // the automatic clock is on it
            // busy enough that the dropouts are the emulator's, not the buffer's: the automatic
            // clock steps in if it gets worse
            s.load > 0.87 -> if (s.autoClock) Problem.None else Problem.CantKeepUp
            lowLatency -> Problem.Buffer
            else -> Problem.Device
        }
        return current
    }

    var current = Problem.None
        private set
}
