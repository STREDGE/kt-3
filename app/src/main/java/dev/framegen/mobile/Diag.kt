package dev.framegen.mobile

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory journal of the last session, shown in the app and copied into the report. */
object Diag {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var gpu: String = ""

    @Synchronized
    fun log(msg: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(clock.format(Date()) + " " + msg)
        Log.i("FrameGen", msg)
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
