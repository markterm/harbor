package com.markwhite.harbor.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What each firmware build wrote to its flash: `saves/<package sha>.sectors`, Harbor's sector
 * store (4-byte offset + 4096 bytes per changed sector; native savefile.rs). Written atomically:
 * a temp file, synced, then renamed over the old one.
 */
class SaveStore(context: Context) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }

    private fun file(sha: String) = File(dir, "$sha.sectors")

    fun read(sha: String): ByteArray? = file(sha).takeIf { it.exists() }?.readBytes()

    /** When the save was last written, or 0. */
    fun savedAt(sha: String): Long = file(sha).takeIf { it.exists() }?.lastModified() ?: 0L

    fun sectorCount(sha: String): Int = (file(sha).takeIf { it.exists() }?.length() ?: 0L).toInt() / RECORD

    @Synchronized
    fun write(sha: String, sectors: ByteArray) {
        val tmp = File(dir, "$sha.tmp")
        FileOutputStream(tmp).use {
            it.write(sectors)
            it.fd.sync()
        }
        if (!tmp.renameTo(file(sha))) throw IOException("could not save flash")
    }

    fun reset(sha: String) {
        file(sha).delete()
    }

    companion object {
        const val RECORD = 4 + 4096
    }
}
