package com.markwhite.harbor.ui.panel

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.markwhite.harbor.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.ui.theme.Tokens

/**
 * Where each key of a keybed is: one or more tiers (rows) of semitone ranges, top tier first.
 * Semitone n (0..26, F3..G5) is matrix key 14 + n. Black keys sit over the gap between whites
 * and are hit-tested first, as in panel.rs.
 */
class KeybedGeometry(val tiers: List<IntRange>, val width: Float, val height: Float, val tierGap: Float = 0f) {
    data class Key(val n: Int, val black: Boolean, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    val keys: List<Key>
    private val whitesFirst: List<Key>
    private val blacksFirst: List<Key>

    init {
        val tierH = (height - tierGap * (tiers.size - 1)) / tiers.size
        val out = ArrayList<Key>()
        tiers.forEachIndexed { t, range ->
            val top = t * (tierH + tierGap)
            val whites = range.filter { !Fm1Panel.isBlack(it) }
            val ww = width / whites.size
            whites.forEachIndexed { i, n -> out += Key(n, false, i * ww, top, (i + 1) * ww, top + tierH) }
            for (n in range) {
                if (!Fm1Panel.isBlack(n)) continue
                val i = whites.indexOf(n - 1).takeIf { it >= 0 } ?: continue
                val cx = (i + 1) * ww
                out += Key(n, true, cx - ww * 0.31f, top, cx + ww * 0.31f, top + tierH * 0.6f)
            }
        }
        keys = out
        whitesFirst = out.sortedBy { it.black }
        blacksFirst = out.sortedBy { !it.black }
    }

    fun hit(x: Float, y: Float): Int? = blacksFirst.firstOrNull { it.contains(x, y) }?.n

    /** In drawing order: whites, then the blacks over them. */
    fun drawOrder(): List<Key> = whitesFirst
}

/** Two tiers for phone portrait: lower F3..E4 (12 keys), upper F4..G5 (15), higher on top. */
val TWO_TIERS = listOf(12..26, 0..11)
val ONE_ROW = listOf(0..26)

/**
 * The note keys (ui-spec §7.3): every pointer independent, glissando for a pointer that started
 * on a key (also across tiers), holds counted per key so two fingers on one key are one press.
 */
@Composable
fun Keybed(tiers: List<IntRange>, leds: PanelLeds, prefs: PanelPrefs, input: PanelInput, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateListOf<Boolean>().apply { repeat(Fm1Panel.KEY_COUNT) { add(false) } } }
    val holds = remember { IntArray(Fm1Panel.KEY_COUNT) }
    val view = LocalView.current
    val p by rememberUpdatedState(prefs)
    val inp by rememberUpdatedState(input)

    fun hold(n: Int, down: Boolean) {
        val before = holds[n]
        holds[n] = (before + if (down) 1 else -1).coerceAtLeast(0)
        if (before == 0 && holds[n] == 1) {
            pressed[n] = true
            inp.key(Fm1Panel.FIRST_KEY_ID + n, true)
            if (p.hapticKeys) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else if (before > 0 && holds[n] == 0) {
            pressed[n] = false
            inp.key(Fm1Panel.FIRST_KEY_ID + n, false)
        }
    }

    // a rotation or layout change must not leave notes hanging
    DisposableEffect(Unit) {
        onDispose {
            for (n in 0 until Fm1Panel.KEY_COUNT) if (holds[n] > 0) {
                holds[n] = 0
                inp.key(Fm1Panel.FIRST_KEY_ID + n, false)
            }
        }
    }

    val context = LocalContext.current
    val typeface = remember { ResourcesCompat.getFont(context, R.font.barlow_condensed_semibold) }
    val namePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val geo = remember(tiers, wPx, hPx) { KeybedGeometry(tiers, wPx, hPx, if (tiers.size > 1) gap else 0f) }

        Canvas(
            Modifier
                .fillMaxSize()
                // cached as a texture: redrawn on a press, not on every frame of the LCD
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                // keys near the screen's edges must not turn into Back or Home gestures
                .systemGestureExclusion()
                .pointerInput(geo) {
                    // pointer -> the key it holds (-1: started off the keys, never plays)
                    val owner = HashMap<PointerId, Int>()
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                for (c in ev.changes) {
                                    val was = owner[c.id]
                                    if (c.pressed) {
                                        val k = geo.hit(c.position.x, c.position.y)
                                        when {
                                            was == null -> { owner[c.id] = k ?: -1; if (k != null) hold(k, true) }
                                            was >= 0 && k != was -> {
                                                hold(was, false)
                                                owner[c.id] = k ?: -1
                                                if (k != null) hold(k, true) else owner[c.id] = -2 // slid off; may slide back on
                                            }
                                            was == -2 && k != null -> { owner[c.id] = k; hold(k, true) }
                                        }
                                        c.consume()
                                    } else if (was != null) {
                                        if (was >= 0) hold(was, false)
                                        owner.remove(c.id)
                                    }
                                }
                            }
                        }
                    } finally {
                        for (k in owner.values) if (k >= 0) hold(k, false)
                        owner.clear()
                    }
                },
        ) {
            val f = prefs.finish
            val cr = 6.dp.toPx()
            val gap = 1.5.dp.toPx()
            for (k in geo.drawOrder()) {
                val down = pressed[k.n]
                if (prefs.flat) {
                    val tl = Offset(k.left + if (k.black) 0f else gap, k.top)
                    val sz = Size(k.right - k.left - if (k.black) 0f else 2 * gap, k.bottom - k.top)
                    drawRoundRect(if (k.black) (if (down) Tokens.amber else Color.Black) else (if (down) Tokens.amberHi else Color.White), tl, sz, CornerRadius(cr))
                    drawRoundRect(if (k.black) Color.White else Color.Black, tl, sz, CornerRadius(cr), style = Stroke(2.dp.toPx()))
                } else if (!k.black) {
                    naturalKey(f, k, gap, cr, down)
                } else {
                    sharpKey(f, k, cr, down)
                }
                val showName = !k.black && (prefs.noteNames == 2 || (prefs.noteNames == 1 && Fm1Panel.noteName(k.n).startsWith("C")))
                if (showName) {
                    namePaint.color = f.naturalInk.toArgb()
                    namePaint.textSize = 12.dp.toPx()
                    namePaint.typeface = typeface
                    drawContext.canvas.nativeCanvas.drawText(Fm1Panel.noteName(k.n), (k.left + k.right) / 2, k.bottom - 14.dp.toPx(), namePaint)
                }
            }
        }

        // the keys' LEDs on a layer of their own: a sequencer stepping through them redraws only this
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val f = prefs.finish
            for (k in geo.keys) {
                val led = leds[Fm1Panel.FIRST_KEY_ID + k.n]
                if (led <= 0f) continue
                // the firmware lights this key: a glowing strip along its front edge
                val w = (k.right - k.left) * 0.42f
                val c = Offset((k.left + k.right) / 2, k.bottom - 7.dp.toPx())
                bloom(c, w, f.accent, 0.5f * led)
                drawRoundRect(f.accent.copy(alpha = 0.5f + 0.5f * led), Offset(c.x - w / 2, c.y - 1.5.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
        }

        // TalkBack: one node per key (drawing and touch are the canvas's)
        for (k in geo.keys) {
            val name = Fm1Panel.spokenName(k.n)
            Box(
                Modifier
                    .offset(with(density) { k.left.toDp() }, with(density) { k.top.toDp() })
                    .size(with(density) { (k.right - k.left).toDp() }, with(density) { (k.bottom - k.top).toDp() })
                    .semantics {
                        role = Role.Button
                        contentDescription = "Key $name"
                        onClick { hold(k.n, true); view.postDelayed({ hold(k.n, false) }, 100); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Hold") { hold(k.n, true); true },
                            CustomAccessibilityAction("Release") { if (holds[k.n] > 0) hold(k.n, false); true },
                        )
                    },
            )
        }
    }
}

/**
 * A silicone natural: a shadow on the tray, a warm body lit from above, and the light that leaks
 * round its edge, which floods it when pressed.
 */
private fun DrawScope.naturalKey(f: Finish, k: KeybedGeometry.Key, gap: Float, cr: Float, down: Boolean) {
    val sink = if (down) 1.dp.toPx() else 0f
    val tl = Offset(k.left + gap, k.top + sink)
    val sz = Size(k.right - k.left - 2 * gap, k.bottom - k.top - 2.dp.toPx())
    softShadow(Offset(tl.x, tl.y + if (down) 0.5.dp.toPx() else 2.dp.toPx()), sz, cr, if (down) 1.5.dp.toPx() else 3.5.dp.toPx(), Color.Black.copy(alpha = 0.7f))
    if (down) softShadow(tl, sz, cr, 8.dp.toPx(), f.keyGlow.copy(alpha = 0.55f))
    val shape = RoundRect(tl.x, tl.y, tl.x + sz.width, tl.y + sz.height, CornerRadius(cr))
    clipPath(Path().apply { addRoundRect(shape) }) {
        val top = if (down) lerp(f.naturalTop, f.keyGlow, 0.18f) else f.naturalTop
        val bot = if (down) lerp(f.naturalBot, f.keyGlow, 0.30f) else f.naturalBot
        drawRect(Brush.verticalGradient(listOf(top, bot), startY = tl.y, endY = tl.y + sz.height), tl, sz)
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.35f), 0.18f to Color.Transparent, startY = tl.y, endY = tl.y + sz.height), tl, sz)
        // the edge glow: rings of falling strength, a cheap inner blur
        val strength = if (down) 1f else 0.6f
        for (i in 0 until 5) {
            val d = i * 1.3.dp.toPx()
            drawRoundRect(
                f.keyGlow.copy(alpha = strength * (1f - i / 5f) * 0.55f),
                Offset(tl.x + d, tl.y + d), Size(sz.width - 2 * d, sz.height - 2 * d), CornerRadius((cr - d).coerceAtLeast(1f)),
                style = Stroke(1.6.dp.toPx()),
            )
        }
        drawRect(Brush.verticalGradient(0.85f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f), startY = tl.y, endY = tl.y + sz.height), tl, sz)
    }
}

/** A glossy black sharp standing over the naturals, its shadow falling on them. */
private fun DrawScope.sharpKey(f: Finish, k: KeybedGeometry.Key, cr: Float, down: Boolean) {
    val sink = if (down) 1.dp.toPx() else 0f
    val tl = Offset(k.left, k.top + sink)
    val sz = Size(k.right - k.left, k.bottom - k.top)
    softShadow(Offset(tl.x + 1.dp.toPx(), tl.y + if (down) 1.dp.toPx() else 4.dp.toPx()), sz, cr, if (down) 2.dp.toPx() else 5.dp.toPx(), Color.Black.copy(alpha = 0.75f))
    if (down) softShadow(tl, sz, cr, 9.dp.toPx(), f.keyGlow.copy(alpha = 0.6f))
    val c = CornerRadius(cr)
    drawRoundRect(Brush.verticalGradient(listOf(if (down) lerp(f.sharpTop, f.keyGlow, 0.2f) else f.sharpTop, f.sharpBot), startY = tl.y, endY = tl.y + sz.height), tl, sz, c)
    // gloss along the top and down the left side
    val inset = 2.5.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), startY = tl.y, endY = tl.y + sz.height * 0.3f),
        Offset(tl.x + inset, tl.y + inset * 0.6f), Size(sz.width - 2 * inset, sz.height * 0.3f), CornerRadius(cr * 0.7f),
    )
    drawRoundRect(Color.White.copy(alpha = 0.05f), Offset(tl.x + inset * 0.6f, tl.y + sz.height * 0.25f), Size(1.5.dp.toPx(), sz.height * 0.6f), CornerRadius(1.dp.toPx()))
    if (down) drawRoundRect(f.keyGlow.copy(alpha = 0.7f), tl, sz, c, style = Stroke(1.2.dp.toPx()))
    else drawRoundRect(Color.Black, tl, sz, c, style = Stroke(1.dp.toPx()))
}
