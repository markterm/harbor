package com.markwhite.harbor.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.markwhite.harbor.engine.EventLog
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings, as WAV files in the shared Music/Harbor folder, where music players and file
 * managers find them. MediaStore lets an app write its own files there without a permission.
 * A recording is written in place, hidden (pending) until it is finished.
 */
class Recordings(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** "Felucca 2026-10-20 14.32.05": sorts by time, and is a valid name on any file system. */
    fun nameFor(firmware: String): String {
        val safe = firmware.replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifEmpty { "Harbor" }
        return "$safe ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date())}"
    }

    /** A new, pending recording and its file, opened for writing and seeking. */
    fun create(name: String, firmware: String): Pair<Uri, ParcelFileDescriptor> {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.ARTIST, firmware)
            put(MediaStore.Audio.Media.ALBUM, "Harbor")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw java.io.IOException("the Music folder isn't available")
        val fd = try {
            resolver.openFileDescriptor(uri, "rw") ?: throw java.io.IOException("couldn't open the recording")
        } catch (e: Exception) {
            delete(uri)
            throw e
        }
        return uri to fd
    }

    /** Show a finished recording to other apps. */
    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /**
     * Recordings left pending when Harbor stopped mid-take (the process was killed): their audio
     * is all there but the WAV header's sizes are still zero. Fill them in and publish, so a crash
     * doesn't cost the take. Call only when no recording is running.
     */
    fun recoverUnfinished() {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(FOLDER))
        }
        val found = runCatching {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                buildList { while (c.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, c.getLong(0))) }
            }
        }.getOrNull().orEmpty()
        for (uri in found) {
            runCatching {
                val ok = resolver.openFileDescriptor(uri, "rw")?.use { fd -> FileOutputStream(fd.fileDescriptor).channel.use(::fixSizes) } == true
                if (ok) publish(uri) else delete(uri)
                EventLog.i(if (ok) "recovered an unfinished recording" else "removed an empty unfinished recording")
            }
        }
    }

    /** Write the RIFF and data sizes from the file's length; false if it holds no audio. */
    private fun fixSizes(ch: java.nio.channels.FileChannel): Boolean {
        val data = ((ch.size() - 44).coerceIn(0, 0xFFFF_FFFFL - 36) / 4) * 4
        if (data == 0L) return false
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).also { it.flip() }
        ch.write(u32(36 + data), 4)
        ch.write(u32(data), 40)
        ch.force(true)
        return true
    }

    companion object {
        /** MediaStore's form of the folder (trailing slash included). */
        val FOLDER = Environment.DIRECTORY_MUSIC + "/Harbor/"
        /** What the UI calls it. */
        const val FOLDER_NAME = "Music/Harbor"
    }
}
