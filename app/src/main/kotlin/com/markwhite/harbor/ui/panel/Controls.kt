package com.markwhite.harbor.ui.panel

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markwhite.harbor.data.KnobGesture
import com.markwhite.harbor.engine.Harbor
import com.markwhite.harbor.ui.theme.Silk
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How the panel's controls behave (ui-spec §6.7). */
data class PanelPrefs(
    val knobGesture: KnobGesture = KnobGesture.Vertical,
    val dpPerDetent: Float = 12f,
    val tapToStep: Boolean = true,
    val hapticKnobs: Boolean = true,
    val hapticButtons: Boolean = true,
    val hapticKeys: Boolean = false,
    val holdAssist: Boolean = false,
    val noteNames: Int = 0,
    val smoothLcd: Boolean = false,
    val flat: Boolean = false,
    val finish: Finish = Graphite,
)

/** What the panel sends to the machine. */
interface PanelInput {
    fun key(id: Int, down: Boolean)
    fun enc(id: Int, clicks: Int)
    fun master(value: Int)
}

private class Haptics(private val view: View) {
    private var last = 0L
    fun tick() {
        val now = System.nanoTime()
        if (now - last < 20_000_000) return // at most one per 20 ms
        last = now
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    fun press() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    fun release() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
    fun stop() = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    fun key() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
private fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** An endless, detented, relative encoder (ui-spec §7.1). The value lives on the LCD. */
@Composable
fun Encoder(enc: Fm1Panel.Enc, size: Dp, prefs: PanelPrefs, input: PanelInput, modifier: Modifier = Modifier, showLabel: Boolean = true, accent: Boolean = false) {
    val haptics = rememberHaptics()
    val slop = LocalViewConfiguration.current.touchSlop
    val pxPerDetent = with(LocalDensity.current) { prefs.dpPerDetent.dp.toPx() }
    var angle by remember { mutableFloatStateOf(0f) }
    var turnedAt by remember { mutableLongStateOf(0L) }
    var glow by remember { mutableStateOf(false) }
    val p by rememberUpdatedState(prefs)
    fun turn(clicks: Int) {
        if (clicks == 0) return
        input.enc(enc.id, clicks)
        angle += 15f * clicks
        turnedAt = System.nanoTime()
        glow = true
        if (p.hapticKnobs) haptics.tick()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            Modifier
                .size(size)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .semantics {
                    contentDescription = "${enc.label} encoder"
                    stateDescription = "Value shown on the firmware screen"
                    customActions = listOf(
                        CustomAccessibilityAction("Turn right") { turn(1); true },
                        CustomAccessibilityAction("Turn left") { turn(-1); true },
                        CustomAccessibilityAction("Turn right 5") { turn(5); true },
                        CustomAccessibilityAction("Turn left 5") { turn(-5); true },
                    )
                }
                .pointerInput(enc.id, pxPerDetent) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val t0 = down.uptimeMillis
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        var acc = 0f
                        var travel = 0f
                        var circular: Boolean? = when (p.knobGesture) {
                            KnobGesture.Vertical -> false
                            KnobGesture.Circular -> true
                            KnobGesture.Both -> null
                        }
                        var lastAngle = atan2(down.position.y - c.y, down.position.x - c.x)
                        var angleAcc = 0f
                        var up = down
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            up = ch
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            ch.consume()
                            travel += abs(d.x) + abs(d.y)
                            if (circular == null && travel > 16.dp.toPx()) {
                                // tangential motion decides: circular; mostly vertical: drag
                                val r = ch.position - c
                                val radial = abs(r.x * d.x + r.y * d.y) / (r.getDistance() + 1e-3f)
                                circular = radial < 0.5f * d.getDistance()
                            }
                            if (circular == true) {
                                val a = atan2(ch.position.y - c.y, ch.position.x - c.x)
                                var da = a - lastAngle
                                if (da > PI) da -= (2 * PI).toFloat()
                                if (da < -PI) da += (2 * PI).toFloat()
                                lastAngle = a
                                angleAcc += Math.toDegrees(da.toDouble()).toFloat()
                                val n = (angleAcc / 15f).toInt()
                                if (n != 0) { angleAcc -= n * 15f; turn(n) }
                            } else if (travel > slop) {
                                acc += -d.y // up is clockwise
                                val n = (acc / pxPerDetent).toInt()
                                if (n != 0) { acc -= n * pxPerDetent; turn(n) }
                            }
                        }
                        // a tap: one precise step (top/right half +1, bottom/left -1)
                        if (p.tapToStep && travel <= slop && up.uptimeMillis - t0 < 250) {
                            val r = down.position - c
                            turn(if (r.x - r.y > 0) 1 else -1)
                        }
                    }
                },
        ) {
            val f = p.finish
            val big = this.size.minDimension / 2f
            val tint = if (accent) f.accent else f.silkDim
            if (glow) bloom(center, big * 1.1f, f.accent, 0.22f)
            ticks(center, big, tint.copy(alpha = if (glow) 1f else if (accent) 0.6f else 0.55f))
            knob(f, center, big * 0.8f, angle, if (accent) f.accent else f.pointer, glowing = accent || glow)
        }
        if (showLabel) Text(enc.short, style = Silk.copy(color = prefs.finish.silk), maxLines = 1)
    }
    // the ring glows for 160 ms after each detent
    LaunchedEffect(turnedAt) { if (turnedAt != 0L) { delay(160); glow = false } }
}

/** MASTER: the only absolute control, 0..1023, 270° travel (ui-spec §7.2). */
@Composable
fun MasterPot(value: Int, size: Dp, prefs: PanelPrefs, input: PanelInput, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val haptics = rememberHaptics()
    val fullTravel = with(LocalDensity.current) { 200.dp.toPx() }
    val v by rememberUpdatedState(value)
    var dragging by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(size)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .semantics {
                        contentDescription = "MASTER"
                        stateDescription = "${(value * 100 / 1023)} percent"
                        progressBarRangeInfo = ProgressBarRangeInfo(value / 1023f, 0f..1f)
                        setProgress { t -> input.master((t * 1023).toInt().coerceIn(0, 1023)); true }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            var acc = v.toFloat()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val before = acc
                                acc = (acc - ch.positionChange().y / fullTravel * 1023f).coerceIn(0f, 1023f)
                                ch.consume()
                                if (acc.toInt() != before.toInt()) {
                                    input.master(acc.toInt())
                                    if (prefs.hapticKnobs && (acc == 0f || acc == 1023f) && before != acc) haptics.stop()
                                }
                            }
                            dragging = false
                        }
                    },
            ) {
                val f = prefs.finish
                val big = this.size.minDimension / 2f
                val t = v / 1023f
                val ring = big * 0.92f
                val tl = Offset(center.x - ring, center.y - ring)
                val ringSize = Size(ring * 2, ring * 2)
                drawArc(Color.Black.copy(alpha = 0.45f), 135f, 270f, false, tl, ringSize, style = Stroke(big * 0.075f, cap = StrokeCap.Round))
                glowArc(center, ring, 135f, 270f * t, big * 0.07f, big * 0.12f, f.accent.copy(alpha = 0.7f))
                if (t > 0f) drawArc(f.accent, 135f, 270f * t, false, tl, ringSize, style = Stroke(big * 0.06f, cap = StrokeCap.Round))
                knob(f, center, big * 0.74f, -135f + 270f * t, f.pointer, glowing = false)
            }
            if (dragging) Text("${value * 100 / 1023}%", style = Silk.copy(fontSize = 11.sp, color = LedAmberHot))
        }
        if (showLabel) Text("MASTER", style = Silk.copy(color = prefs.finish.silk), maxLines = 1)
    }
}

/** A momentary button with its LED (ui-spec §7.4). The firmware owns long-press and combos. */
@Composable
fun PanelButton(btn: Fm1Panel.Btn, led: Float, width: Dp, height: Dp, prefs: PanelPrefs, input: PanelInput, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    var down by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }
    val p by rememberUpdatedState(prefs)
    fun press(d: Boolean) {
        if (d == down) return
        down = d
        input.key(btn.id, d)
        if (p.hapticButtons) if (d) haptics.press() else haptics.release()
    }
    Box(
        modifier
            .size(width, height)
            .semantics {
                role = Role.Button
                contentDescription = "${btn.label.replace("−", " minus").replace("+", " plus")} button"
                stateDescription = when {
                    latched -> "held"
                    led > 0.5f -> "light on"
                    else -> "light off"
                }
                onClick {
                    if (p.holdAssist) { latched = !latched; press(latched) } else { press(true); press(false) }
                    true
                }
            }
            .pointerInput(btn.id) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    if (p.holdAssist) {
                        latched = !latched
                        press(latched)
                    } else press(true)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                    }
                    if (!p.holdAssist) press(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val f = p.finish
        val lit = led > 0.02f
        val light = if (btn.id == 12) LedGreen else f.accent
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val cr = CornerRadius(9.dp.toPx())
            val body = Size(this.size.width, this.size.height - 2.dp.toPx())
            val sink = if (down) 1.5.dp.toPx() else 0f
            softShadow(Offset(0f, if (down) 1.dp.toPx() else 2.5.dp.toPx()), body, cr.x, if (down) 1.5.dp.toPx() else 4.dp.toPx(), Color.Black.copy(alpha = 0.6f))
            if (lit) softShadow(Offset.Zero, body, cr.x, 9.dp.toPx(), light.copy(alpha = 0.45f * led * led))
            translate(top = sink) {
                drawRoundRect(Brush.verticalGradient(listOf(if (down) f.capBot else f.capTop, f.capBot)), size = body, cornerRadius = cr)
                drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.05f), 0.5f to Color.Transparent), size = body, cornerRadius = cr)
                // the rim and tint follow the LED's brightness: a firmware's dim "available" glow
                // reads as dim, its full "on" as the lit amber
                drawRoundRect(Brush.verticalGradient(listOf(f.capRim, f.capRim.copy(alpha = 0.25f))), size = body, cornerRadius = cr, style = Stroke(1.dp.toPx()))
                if (lit) {
                    drawRoundRect(Brush.verticalGradient(0f to light.copy(alpha = 0.16f * led), 0.7f to Color.Transparent), size = body, cornerRadius = cr)
                    drawRoundRect(light.copy(alpha = 0.15f + 0.85f * led), size = body, cornerRadius = cr, style = Stroke((1f + 0.6f * led).dp.toPx()))
                }
                if (latched) drawRoundRect(f.accent, size = body, cornerRadius = cr, style = Stroke(2.dp.toPx()))
                // the LED: shown on OCT always (it's how they read), on the rest when lit
                val c = Offset(this.size.width / 2f, 6.5.dp.toPx())
                if (lit) {
                    bloom(c, 9.dp.toPx(), light, 0.6f * led)
                    drawCircle(Color.Black.copy(alpha = 0.55f), 2.6.dp.toPx(), c)
                    drawCircle(Brush.radialGradient(listOf(if (btn.id == 12) LedGreenHot else LedAmberHot, light), center = c, radius = 2.4.dp.toPx()), 2.4.dp.toPx(), c,
                        alpha = 0.35f + 0.65f * led)
                } else if (btn.id < 2) {
                    drawCircle(Color.Black.copy(alpha = 0.55f), 2.6.dp.toPx(), c)
                    drawCircle(light.copy(alpha = 0.22f), 1.8.dp.toPx(), c)
                }
            }
        }
        val ink = if (lit) androidx.compose.ui.graphics.lerp(f.capInk, light, led) else f.capInk
        val icon = PanelIcons.forButton(btn.id)
        Column(
            Modifier.offset(y = if (down) 2.5.dp else 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (icon != null && height >= 44.dp) Icon(icon, null, Modifier.size(17.dp), tint = ink.copy(alpha = if (lit) 1f else 0.85f))
            Text(btn.label.replace("−", " −").replace("+", " +"), style = Silk.copy(fontSize = 11.sp, color = ink), maxLines = 1)
        }
    }
}

/** The firmware's screen, pixel-exact (ui-spec §5.1.2). */
@Composable
fun LcdView(harbor: Harbor, size: Dp, smooth: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember { Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565).apply { eraseColor(android.graphics.Color.BLACK) } }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(harbor) {
        var rev = -1L
        while (true) {
            withFrameNanos { }
            val now = harbor.pollLcd(bitmap, rev)
            if (now != rev) { rev = now; frame++ }
        }
    }
    val px = with(LocalDensity.current) { size.roundToPx() }
    // Pixel-exact at a whole multiple of 240. Between multiples, nearest-neighbour alone makes some
    // LCD pixels a device pixel wider than others (strokes look uneven), so draw at the next whole
    // multiple and let the layer's scale-down blend just the seams.
    val k = (px + 239) / 240
    val exact = smooth || px % 240 == 0
    Box(modifier.size(size).clipToBounds().semantics { contentDescription = "Firmware screen" }) {
        val drawn = if (exact) px else 240 * k
        val scale = px.toFloat() / drawn
        // its own layer: the firmware redraws the screen at up to 60 Hz, and nothing else should
        Canvas(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(LocalDensity.current) { drawn.toDp() })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    compositingStrategy = if (exact) CompositingStrategy.Auto else CompositingStrategy.Offscreen
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") frame
            drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(240, 240), dstOffset = IntOffset.Zero, dstSize = IntSize(drawn, drawn),
                filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None)
        }
    }
}

/**
 * The 41 LED lines (buttons 0..13, keys 14..40). Each is its own state, so a change redraws only
 * what it lights, never the whole panel.
 */
@Stable
class PanelLeds {
    private val levels = Array(41) { mutableFloatStateOf(0f) }
    operator fun get(i: Int): Float = levels.getOrNull(i)?.floatValue ?: 0f
    internal fun set(i: Int, v: Float) {
        if (levels[i].floatValue != v) levels[i].floatValue = v
    }
}

/** Polled at about 30 Hz; levels are snapped to sixteenths so a fading LED settles. */
@Composable
fun rememberLeds(harbor: Harbor): PanelLeds {
    val leds = remember { PanelLeds() }
    LaunchedEffect(harbor) {
        val buf = FloatArray(41)
        while (true) {
            harbor.pollLeds(buf)
            for (i in buf.indices) leds.set(i, if (buf[i] < 0.04f) 0f else (buf[i] * 16).roundToInt() / 16f)
            delay(33)
        }
    }
    return leds
}

/** The scale printed round a knob: [count] marks over its 270° travel. */
private fun DrawScope.ticks(c: Offset, big: Float, color: Color, count: Int = 11) {
    for (i in 0 until count) {
        val a = Math.toRadians(-135.0 + 270.0 * i / (count - 1))
        val s = sin(a).toFloat()
        val co = cos(a).toFloat()
        drawLine(color, Offset(c.x + s * big * 0.90f, c.y - co * big * 0.90f), Offset(c.x + s * big * 0.99f, c.y - co * big * 0.99f),
            strokeWidth = big * 0.04f, cap = StrokeCap.Round)
    }
}

/**
 * A knob from above, lit from over the top edge: its shadow, a knurled skirt that turns with it,
 * a glossy face whose highlight doesn't, and the pointer ([angle] degrees, 0 up, clockwise).
 */
private fun DrawScope.knob(f: Finish, c: Offset, r: Float, angle: Float, pointer: Color, glowing: Boolean) {
    softShadow(Offset(c.x, c.y + r * 0.16f), r * 0.98f, r * 0.22f, Color.Black.copy(alpha = 0.75f))
    drawCircle(Brush.verticalGradient(listOf(f.knobHi, f.knobLo), startY = c.y - r, endY = c.y + r), r, c)
    val knurls = 44
    for (i in 0 until knurls) {
        val a = Math.toRadians(i * 360.0 / knurls + angle)
        val s = sin(a).toFloat()
        val co = cos(a).toFloat()
        val facing = 0.5f + 0.5f * co // ridges facing the light catch it
        drawLine(Color.White.copy(alpha = 0.02f + 0.10f * facing), Offset(c.x + s * r * 0.87f, c.y - co * r * 0.87f),
            Offset(c.x + s * r * 0.985f, c.y - co * r * 0.985f), strokeWidth = r * 0.035f)
    }
    val face = r * 0.82f
    drawCircle(Color.Black.copy(alpha = 0.65f), face + r * 0.03f, c)
    drawCircle(Brush.radialGradient(listOf(f.knobHi, f.knobLo), center = Offset(c.x - face * 0.2f, c.y - face * 0.55f), radius = face * 1.6f), face, c)
    // the face's rim catches the light along the top; a soft glint sits up and to the left
    val rim = face * 0.94f
    drawArc(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), startY = c.y - face, endY = c.y + face * 0.1f),
        195f, 150f, false, Offset(c.x - rim, c.y - rim), Size(rim * 2, rim * 2), style = Stroke(r * 0.045f, cap = StrokeCap.Round),
    )
    bloom(Offset(c.x - face * 0.25f, c.y - face * 0.5f), face * 0.6f, Color.White, 0.08f)
    val a = Math.toRadians(angle.toDouble())
    val s = sin(a).toFloat()
    val co = cos(a).toFloat()
    val p0 = Offset(c.x + s * face * 0.32f, c.y - co * face * 0.32f)
    val p1 = Offset(c.x + s * face * 0.86f, c.y - co * face * 0.86f)
    if (glowing) glowLine(p0, p1, r * 0.1f, r * 0.14f, pointer.copy(alpha = 0.8f))
    drawLine(pointer, p0, p1, strokeWidth = r * 0.09f, cap = StrokeCap.Round)
}

/**
 * The LCD's frame: a black bezel standing proud of the case, the panel set into it, and glass
 * over the panel. [content] gets the panel's size.
 */
@Composable
fun LcdBezel(size: Dp, modifier: Modifier = Modifier, content: @Composable (Dp) -> Unit) {
    val bezel = (size * 0.045f).coerceIn(6.dp, 14.dp)
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(size)
            .dropShadow(shape, Shadow(radius = 14.dp, color = Color.Black, offset = DpOffset(0.dp, 5.dp), alpha = 0.6f))
            .drawBehind {
                val cr = CornerRadius(14.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1C1D20), Color(0xFF0B0B0D))), cornerRadius = cr)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f))),
                    cornerRadius = cr, style = Stroke(1.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val inner = size - bezel * 2
        Box(
            Modifier
                .size(inner)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black),
        ) {
            content(inner)
            Canvas(Modifier.matchParentSize()) {
                // glass: a faint sheen across the upper left, gone by the middle
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.07f), 0.42f to Color.White.copy(alpha = 0.02f), 0.43f to Color.Transparent,
                        start = Offset.Zero, end = Offset(this.size.width, this.size.height),
                    ),
                )
            }
        }
    }
}
