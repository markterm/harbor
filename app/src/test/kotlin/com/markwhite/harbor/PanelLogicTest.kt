package com.markwhite.harbor

import androidx.compose.ui.unit.dp
import com.markwhite.harbor.data.Catalog
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.engine.EngineStatus
import com.markwhite.harbor.ui.LayoutMode
import com.markwhite.harbor.ui.PerfWatch
import com.markwhite.harbor.ui.layoutModeOf
import com.markwhite.harbor.ui.panel.Fm1Panel
import com.markwhite.harbor.ui.panel.KeybedGeometry
import com.markwhite.harbor.ui.panel.ONE_ROW
import com.markwhite.harbor.ui.panel.TWO_TIERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLogicTest {
    @Test
    fun layoutModesFollowWidthAndHeight() {
        assertEquals(LayoutMode.CompactPortrait, layoutModeOf(412.dp, 915.dp))
        assertEquals(LayoutMode.CompactPortrait, layoutModeOf(360.dp, 780.dp))
        // phone landscape is compact height, not medium width (ui-spec §5.1.5)
        assertEquals(LayoutMode.CompactLandscape, layoutModeOf(915.dp, 412.dp))
        assertEquals(LayoutMode.Medium, layoutModeOf(800.dp, 1280.dp))
        assertEquals(LayoutMode.Medium, layoutModeOf(673.dp, 841.dp))
        assertEquals(LayoutMode.Expanded, layoutModeOf(1280.dp, 800.dp))
    }

    @Test
    fun noteNamesCoverF3ToG5() {
        assertEquals("F3", Fm1Panel.noteName(0))
        assertEquals("F♯3", Fm1Panel.noteName(1))
        assertEquals("B3", Fm1Panel.noteName(6))
        assertEquals("C4", Fm1Panel.noteName(7))
        assertEquals("E4", Fm1Panel.noteName(11))
        assertEquals("F4", Fm1Panel.noteName(12))
        assertEquals("G5", Fm1Panel.noteName(26))
        assertEquals("F sharp 3", Fm1Panel.spokenName(1).replace("  ", " ").trim())
        assertEquals(16, (0 until Fm1Panel.KEY_COUNT).count { !Fm1Panel.isBlack(it) })
    }

    @Test
    fun oneRowHasEveryKeyOnceAndBlacksWinTheOverlap() {
        val g = KeybedGeometry(ONE_ROW, 1600f, 200f)
        assertEquals((0 until 27).toList(), g.keys.map { it.n }.sorted())
        val ww = 100f
        // the top of the boundary between F (0) and G (2) is the F♯ black key
        assertEquals(1, g.hit(ww, 10f))
        // lower down the same x is white again (F or G)
        assertTrue(g.hit(ww - 5f, 190f) == 0)
        assertTrue(g.hit(ww + 5f, 190f) == 2)
        // B3 to C4 and E4 to F4 have no black key between them
        assertEquals(6, g.hit(4 * ww - 2f, 10f))
        assertEquals(7, g.hit(4 * ww + 2f, 10f))
        assertNull(g.hit(1600f, 10f))
    }

    @Test
    fun twoTiersPutTheUpperOctavesOnTop() {
        val g = KeybedGeometry(TWO_TIERS, 900f, 400f, tierGap = 0f)
        assertEquals((0 until 27).toList(), g.keys.map { it.n }.sorted())
        // upper tier F4..G5 (9 whites, 100 px each), lower F3..E4 (7 whites)
        assertEquals(12, g.hit(10f, 190f))
        assertEquals(26, g.hit(890f, 190f))
        assertEquals(0, g.hit(10f, 390f))
        assertEquals(11, g.hit(890f, 390f))
    }

    @Test
    fun perfBannerWaitsFiveSecondsAndLeavesClockProblemsToTheAutomaticClock() {
        val w = PerfWatch()
        var t = 0L
        var starved = 0L
        fun second(load: Double, mhz: Int, auto: Boolean, drop: Boolean): PerfWatch.Problem {
            t += 1000
            if (drop) starved += 500
            return w.update(EngineStatus(speed = 1 / load, mhz = mhz, autoClock = auto, starved = starved), lowLatency = true, nowMs = t)
        }
        repeat(4) { assertEquals(PerfWatch.Problem.None, second(1.2, 240, true, true)) }
        // still dropping at 240 with the automatic clock on: no banner, the clock steps instead
        repeat(3) { assertEquals(PerfWatch.Problem.None, second(1.2, 240, true, true)) }
        repeat(6) { second(1.2, 120, true, true) }
        assertEquals(PerfWatch.Problem.CantKeepUp, second(1.2, 120, true, true))
        // dropouts with time to spare on the low-latency buffer: suggest the larger one
        val w2 = PerfWatch()
        t = 0; starved = 0
        repeat(7) { t += 1000; starved += 100; w2.update(EngineStatus(speed = 2.0, starved = starved), true, t) }
        assertEquals(PerfWatch.Problem.Buffer, w2.current)
    }

    @Test
    fun theBundledCatalogParses() {
        val text = javaClass.classLoader!!.getResource("firmwares.json")!!.readText()
        val c = Catalog.parse(text)
        assertTrue(c.firmwares.size >= 40)
        val stock = c.firmwares.first { it.id == "mvave" }
        assertTrue(stock.byo)
        assertTrue(stock.playable)
        val felucca = c.firmwares.first { it.id == c.default }
        assertTrue(felucca.`package`!!.endsWith(".fwsc"))
        val april = c.firmwares.first { it.id == "april" }
        assertFalse(april.playable)
    }

    @Test
    fun firmwareNotes() {
        assertEquals(1, FirmwareNotes.preferredChannel("mvave"))
        assertTrue(FirmwareNotes.makesNoSound("ctl"))
        assertTrue(FirmwareNotes.midiHint("felucca")!!.contains("1–4"))
    }
}
