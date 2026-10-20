package com.markwhite.harbor.engine

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the engine has been doing: boots, state changes, clock steps, audio restarts, pauses and
 * a periodic health line. Each line goes to logcat (tag "Harbor") and to a ring of the most recent
 * lines that "Copy a report" includes, so a firmware that got into a bad state can be traced
 * after the fact.
 */
object EventLog {
    private const val TAG = "Harbor"
    private const val KEEP = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun i(msg: String) = add('I', msg)
    fun w(msg: String) = add('W', msg)

    @Synchronized
    private fun add(level: Char, msg: String) {
        if (level == 'W') Log.w(TAG, msg) else Log.i(TAG, msg)
        lines.addLast("${time.format(Date())} $level $msg")
        while (lines.size > KEEP) lines.removeFirst()
    }

    /** The most recent [n] lines, oldest first. */
    @Synchronized
    fun recent(n: Int = 120): List<String> = lines.toList().takeLast(n)
}
