package com.markwhite.harbor.ui.panel

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The panel's materials. The FM-1 is sold in several colours; [Graphite] is Harbor's default and
 * [Dusk] keeps fm1-emu's own panel colours.
 */
data class Finish(
    val id: String,
    val label: String,
    val bodyTop: Color,
    val bodyBot: Color,
    /** the lit top edge of the case */
    val bodyEdge: Color,
    val tray: Color,
    val trayRim: Color,
    val silk: Color,
    val silkDim: Color,
    val capTop: Color,
    val capBot: Color,
    val capRim: Color,
    val capInk: Color,
    val naturalTop: Color,
    val naturalBot: Color,
    val naturalInk: Color,
    /** the light that leaks round a silicone key's edge */
    val keyGlow: Color,
    val sharpTop: Color,
    val sharpBot: Color,
    val knobHi: Color,
    val knobLo: Color,
    val pointer: Color,
    val accent: Color,
    /** how strongly the grain shows: dark finishes need less */
    val grain: Float,
)

val Graphite = Finish(
    id = "graphite", label = "Graphite",
    bodyTop = Color(0xFF2D2E32), bodyBot = Color(0xFF222326), bodyEdge = Color(0x33FFFFFF),
    tray = Color(0xFF121315), trayRim = Color(0xFF34353A),
    silk = Color(0xFFDAD7D0), silkDim = Color(0xFF7E7C77),
    capTop = Color(0xFF2E2F33), capBot = Color(0xFF232427), capRim = Color(0xFF3D3E43), capInk = Color(0xFFDAD7D0),
    naturalTop = Color(0xFFF4EBDF), naturalBot = Color(0xFFE5D3BF), naturalInk = Color(0xFF7A6A58), keyGlow = Color(0xFFFFA24A),
    sharpTop = Color(0xFF2B2C30), sharpBot = Color(0xFF0D0D0F),
    knobHi = Color(0xFF3F4045), knobLo = Color(0xFF0F1012), pointer = Color(0xFFF4F1EA), accent = Color(0xFFFFA43A),
    grain = 0.04f,
)

val Dusk = Finish(
    id = "dusk", label = "Dusk",
    bodyTop = Color(0xFF5E6166), bodyBot = Color(0xFF4A4D52), bodyEdge = Color(0x4DFFFFFF),
    tray = Color(0xFF26272A), trayRim = Color(0xFF6A6D72),
    silk = Color(0xFFE9E6DE), silkDim = Color(0xFFB5B2AB),
    capTop = Color(0xFF4B4E54), capBot = Color(0xFF3B3D42), capRim = Color(0xFF63666C), capInk = Color(0xFFE9E6DE),
    naturalTop = Color(0xFF7A6366), naturalBot = Color(0xFF5A4749), naturalInk = Color(0xFFE6D6D7), keyGlow = Color(0xFFFFB27A),
    sharpTop = Color(0xFF3E3234), sharpBot = Color(0xFF2A2123),
    knobHi = Color(0xFF55585E), knobLo = Color(0xFF26272A), pointer = Color(0xFFF4F1EA), accent = Color(0xFFF2A33A),
    grain = 0.06f,
)

val Arctic = Finish(
    id = "arctic", label = "Arctic",
    bodyTop = Color(0xFFEDEBE6), bodyBot = Color(0xFFDAD7D0), bodyEdge = Color(0xB3FFFFFF),
    tray = Color(0xFF2A2B2E), trayRim = Color(0xFFBEBBB4),
    silk = Color(0xFF3A3B3F), silkDim = Color(0xFF8D8A84),
    capTop = Color(0xFFF8F6F2), capBot = Color(0xFFE2DFD8), capRim = Color(0xFFC9C6BF), capInk = Color(0xFF36373B),
    naturalTop = Color(0xFFFBF9F5), naturalBot = Color(0xFFE6E2DA), naturalInk = Color(0xFF6A675F), keyGlow = Color(0xFFFFB766),
    sharpTop = Color(0xFF3C3E43), sharpBot = Color(0xFF1A1B1E),
    knobHi = Color(0xFF4A4C52), knobLo = Color(0xFF16171A), pointer = Color(0xFFF4F1EA), accent = Color(0xFFF08A1C),
    grain = 0.07f,
)

val Finishes = listOf(Graphite, Dusk, Arctic)
fun finishOf(id: String) = Finishes.firstOrNull { it.id == id } ?: Graphite

val LocalFinish = staticCompositionLocalOf { Graphite }

// ------------------------------------------------------------------------------ light

val LedAmber = Color(0xFFFFA43A)
val LedAmberHot = Color(0xFFFFE3B0)
val LedGreen = Color(0xFF6BE07A)
val LedGreenHot = Color(0xFFD8FFDC)

/** A soft shadow under a rounded rect, blurred on the render thread (BlurMaskFilter, API 28+). */
fun DrawScope.softShadow(topLeft: Offset, size: Size, radius: Float, blur: Float, color: Color) {
    if (blur <= 0f) return
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    drawContext.canvas.nativeCanvas.drawRoundRect(
        topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, radius, radius, paint,
    )
}

/** A line that glows: [color] blurred by [blur] px, for lit pointers and arcs. */
fun DrawScope.glowLine(from: Offset, to: Offset, width: Float, blur: Float, color: Color) {
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    paint.strokeWidth = width
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    drawContext.canvas.nativeCanvas.drawLine(from.x, from.y, to.x, to.y, paint)
}

/** The same for an arc (degrees, clockwise from three o'clock). */
fun DrawScope.glowArc(center: Offset, radius: Float, start: Float, sweep: Float, width: Float, blur: Float, color: Color) {
    if (sweep <= 0f) return
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = width
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    drawContext.canvas.nativeCanvas.drawArc(center.x - radius, center.y - radius, center.x + radius, center.y + radius, start, sweep, false, paint)
    paint.style = android.graphics.Paint.Style.FILL
}

/** The same for a disc. */
fun DrawScope.softShadow(center: Offset, radius: Float, blur: Float, color: Color) {
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, radius, paint)
}

/** A light's bloom: a radial falloff from [color] to nothing. */
fun DrawScope.bloom(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0f) return
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha), 0.35f to color.copy(alpha = alpha * 0.45f), 1f to color.copy(alpha = 0f),
            center = center, radius = radius,
        ),
        radius, center,
    )
}

private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private val filters = HashMap<Int, BlurMaskFilter>()
private fun blurFilter(blur: Float): BlurMaskFilter = filters.getOrPut((blur * 4).toInt().coerceAtLeast(1)) {
    BlurMaskFilter(blur.coerceAtLeast(0.25f), BlurMaskFilter.Blur.NORMAL)
}

/** Fine monochrome noise, tiled over the case so it reads as bead-blasted aluminium. */
private val grainBrush: ShaderBrush by lazy {
    val n = 160
    val rnd = Random(7)
    val px = IntArray(n * n) {
        val v = rnd.nextInt(256)
        android.graphics.Color.argb(255, v, v, v)
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

fun DrawScope.grain(alpha: Float) {
    drawRect(grainBrush, alpha = alpha, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay)
}

// ------------------------------------------------------------------------------ surfaces

/**
 * The case: a lit top edge, a gradient from the light above, grain, and a shadow on the desk.
 * [corner] 0 runs it edge to edge.
 */
fun Modifier.caseBody(finish: Finish, corner: Dp = 26.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    val lifted = if (corner > 0.dp) dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(0.dp, 10.dp), alpha = 0.55f)) else this
    return lifted
        .drawBehind {
            val cr = CornerRadius(corner.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(finish.bodyTop, finish.bodyBot)), cornerRadius = cr)
            // the lamp above: a soft highlight across the upper third
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.35f, -size.height * 0.05f), radius = size.maxDimension * 0.8f,
                ),
                cornerRadius = cr,
            )
            grain(finish.grain)
            // chamfer: a bright line along the top, a dark one along the bottom (not when full-bleed)
            if (corner > 0.dp) drawRoundRect(
                Brush.verticalGradient(0f to finish.bodyEdge, 0.08f to Color.Transparent, 0.92f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)),
                cornerRadius = cr, style = Stroke(1.2.dp.toPx()),
            )
        }
}

/** A recessed well in the case: darker, shadowed from the top edge, lit along the bottom lip. */
fun Modifier.tray(finish: Finish, corner: Dp = 14.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .drawBehind {
            val cr = CornerRadius(corner.toPx())
            drawRoundRect(finish.tray, cornerRadius = cr)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), finish.trayRim)),
                cornerRadius = cr, style = Stroke(1.dp.toPx()),
            )
        }
        .innerShadow(shape, Shadow(radius = 10.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp), alpha = 0.75f))
}
