package com.markwhite.harbor.ui.panel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Glyphs for the twelve LED buttons, read off their silkscreen names. Firmwares repurpose the
 * buttons, so these only illustrate the label; the label stays. One stroke weight throughout.
 */
object PanelIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = null, pathBuilder = block,
    )

    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = block)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy); arcToRelative(r, r, 0f, true, true, 2 * r, 0f); arcToRelative(r, r, 0f, true, true, -2 * r, 0f); close()
    }

    /** FX: a cube */
    private val fx = icon("fx") {
        line {
            moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
            moveTo(4f, 7.5f); lineTo(12f, 12f); lineTo(20f, 7.5f)
            moveTo(12f, 12f); lineTo(12f, 21f)
        }
    }

    /** SCL: stacked layers */
    private val scl = icon("scl") {
        line { moveTo(12f, 3.5f); lineTo(21f, 8f); lineTo(12f, 12.5f); lineTo(3f, 8f); close() }
        line { moveTo(3f, 12f); lineTo(12f, 16.5f); lineTo(21f, 12f) }
        line { moveTo(3f, 16f); lineTo(12f, 20.5f); lineTo(21f, 16f) }
    }

    /** ENV: attack, decay, sustain, release */
    private val env = icon("env") {
        line { moveTo(2.5f, 19f); lineTo(8f, 5f); lineTo(11.5f, 12f); lineTo(16f, 12f); lineTo(21.5f, 19f) }
    }

    /** LFO: one cycle of a sine */
    private val lfo = icon("lfo") {
        line { moveTo(2.5f, 12f); curveTo(5.5f, 1.5f, 9f, 1.5f, 12f, 12f); curveTo(15f, 22.5f, 18.5f, 22.5f, 21.5f, 12f) }
    }

    /** EDIT: a pencil */
    private val edit = icon("edit") {
        line {
            moveTo(15.5f, 4.5f); lineTo(19.5f, 8.5f); lineTo(8.5f, 19.5f); lineTo(4f, 20f); lineTo(4.5f, 15.5f); close()
            moveTo(13f, 7f); lineTo(17f, 11f)
        }
    }

    /** GLO: a gear */
    private val glo = icon("glo") {
        line { circle(12f, 12f, 3f) }
        line {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val (s, c) = Math.sin(a).toFloat() to Math.cos(a).toFloat()
                moveTo(12f + c * 6.2f, 12f + s * 6.2f); lineTo(12f + c * 9f, 12f + s * 9f)
            }
            circle(12f, 12f, 6.2f)
        }
    }

    /** HOME: a house */
    private val home = icon("home") {
        line {
            moveTo(3.5f, 11f); lineTo(12f, 4f); lineTo(20.5f, 11f)
            moveTo(6f, 9.5f); lineTo(6f, 20f); lineTo(10f, 20f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20f); lineTo(18f, 20f); lineTo(18f, 9.5f)
        }
    }

    /** SAVE: a disk */
    private val save = icon("save") {
        line {
            moveTo(4f, 4f); lineTo(16.5f, 4f); lineTo(20f, 7.5f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(8f, 4f); lineTo(8f, 9f); lineTo(15f, 9f); lineTo(15f, 4f)
        }
        line { circle(12f, 15f, 2.3f) }
    }

    /** ARP: rising steps */
    private val arp = icon("arp") {
        solid {
            for ((i, h) in listOf(5f, 9f, 13f, 17f).withIndex()) {
                val x = 3.5f + i * 4.6f
                moveTo(x, 20.5f - h); lineTo(x + 3f, 20.5f - h); lineTo(x + 3f, 20.5f); lineTo(x, 20.5f); close()
            }
        }
    }

    /** SEQ: a grid of steps */
    private val seq = icon("seq") {
        solid {
            for (r in 0 until 3) for (c in 0 until 3) {
                val x = 4.5f + c * 6f
                val y = 4.5f + r * 6f
                moveTo(x, y); lineTo(x + 3.2f, y); lineTo(x + 3.2f, y + 3.2f); lineTo(x, y + 3.2f); close()
            }
        }
    }

    private val play = icon("play") { solid { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() } }

    private val rec = icon("rec") { solid { circle(12f, 12f, 6.5f) } }

    /** By button id (2..13); OCT has none. */
    fun forButton(id: Int): ImageVector? = when (id) {
        2 -> fx; 3 -> scl; 4 -> env; 5 -> lfo; 6 -> edit; 7 -> glo
        8 -> home; 9 -> save; 10 -> arp; 11 -> seq; 12 -> play; 13 -> rec
        else -> null
    }
}
