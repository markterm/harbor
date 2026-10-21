package com.markwhite.harbor.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Firmware screenshots from the Hub, kept on the device. Each image is fetched once, kept in the
 * cache directory and checked again a week later with its ETag, so a changed one comes through
 * without downloading the rest. A missing, oversized or undecodable image is simply none: the
 * Library falls back to the firmware's monogram, and this run of the app doesn't ask again for a
 * day.
 */
class Screenshots(context: Context) {
    private val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
    // decoded at most MAX_SIDE a side, so 32 of them is a few MB at most
    private val memory = LruCache<String, ImageBitmap>(32)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    // a list scrolling past dozens of cards shouldn't open dozens of connections
    private val network = Semaphore(3)

    /** The image if it is already decoded, for drawing without a frame's delay. */
    fun cached(url: String): ImageBitmap? = memory.get(url)

    /** The image at [url], from memory, disk or the network; null when there isn't a usable one. */
    suspend fun load(url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = loading.putIfAbsent(url, mine)
        if (running != null) return running.await()
        // whoever is waiting is released even if this caller is cancelled (its card scrolled away)
        var result: ImageBitmap? = null
        try {
            result = withContext(Dispatchers.IO) { runCatching { fetchAndDecode(url) }.getOrNull() }
            if (result != null) memory.put(url, result)
            return result
        } finally {
            loading.remove(url)
            mine.complete(result)
        }
    }

    private suspend fun fetchAndDecode(url: String): ImageBitmap? {
        val key = sha1(url)
        val file = File(dir, "$key.img")
        val tag = File(dir, "$key.etag")
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > REVALIDATE_MS
        val recentlyFailed = failedAt[url]?.let { System.currentTimeMillis() - it < RETRY_MS } == true
        if (stale && !recentlyFailed) {
            // no network is tried again next time; only the Hub saying there's no usable image waits
            val ok = try { network.withPermit { download(url, file, tag) } } catch (_: java.io.IOException) { null }
            if (ok == false) failedAt[url] = System.currentTimeMillis()
        }
        // a failed revalidation keeps the copy we have
        val bitmap = if (file.exists()) decode(file) else null
        if (bitmap == null && file.exists()) { file.delete(); tag.delete() }
        return bitmap
    }

    /** True when [file] now holds the current image (fresh, or confirmed unchanged). */
    private fun download(url: String, file: File, tag: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (file.exists()) runCatching { tag.readText() }.getOrNull()?.let { conn.setRequestProperty("If-None-Match", it) }
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> { file.setLastModified(System.currentTimeMillis()); return true }
                HttpURLConnection.HTTP_OK -> Unit
                else -> return false
            }
            if (conn.contentType?.startsWith("image/") == false) return false
            if (conn.contentLengthLong > MAX_BYTES) return false
            val tmp = File(file.path + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 shl 10)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tmp.delete(); return false }
                        out.write(buf, 0, n)
                    }
                }
            }
            // check it decodes before it replaces a good copy
            if (!decodesAtAll(tmp)) { tmp.delete(); return false }
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) return false }
            conn.getHeaderField("ETag")?.let { tag.writeText(it) } ?: tag.delete()
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun decodesAtAll(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /** Decoded down to at most [MAX_SIDE] a side: the Library never shows one larger. */
    private fun decode(file: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 2L shl 20
        const val MAX_SIDE = 800
        const val REVALIDATE_MS = 7 * 24 * 3600_000L
        const val RETRY_MS = 24 * 3600_000L
    }
}
