package com.markwhite.harbor

import com.markwhite.harbor.data.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fm1-emu's list brought up to date from the hub's (the snapshots in test resources). */
class CatalogMergeTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val listed = Catalog.parse(text("firmwares.json"))
    private val merged = Catalog.merge(listed, Catalog.parseHub(text("hub-catalog.json")))

    @Test
    fun aListedFirmwareTakesTheHubsCurrentPackage() {
        val amb = merged.firmwares.first { it.id == "amb" }
        assertEquals("1.3", amb.version)
        assertEquals("https://thefirmwarehub.com/fw/amb-1.3.fwsc", amb.`package`)
        assertNotNull(amb.sha256)
        assertEquals("https://thefirmwarehub.com/fw/amb-1.2.fwsc", amb.listedPackage)
        assertFalse(amb.hubOnly)
    }

    @Test
    fun bringYourOwnStaysAsListed() {
        val stock = merged.firmwares.first { it.id == "mvave" }
        assertNull(stock.`package`)
        assertTrue(stock.byo)
    }

    @Test
    fun theHubsOtherFirmwaresFollowUnchecked() {
        val listedIds = listed.firmwares.map { it.id }.toSet()
        val extra = merged.firmwares.filter { it.hubOnly }
        assertTrue(extra.isNotEmpty())
        assertTrue(extra.all { it.id !in listedIds && it.`package`!!.startsWith(Catalog.HUB) })
        // already listed under another id, or needing M-VAVE's loader: not repeated or offered
        assertFalse(extra.any { it.id == "fm1quest" || it.id == "purplemonkey" || it.id == "bubba" })
        assertEquals(merged.firmwares.size, merged.firmwares.map { it.id }.toSet().size)
    }
}
