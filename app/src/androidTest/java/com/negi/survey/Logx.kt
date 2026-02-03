@file:Suppress("unused")

package com.negi.survey

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/* ---------- chunked / boxed logger (ANDROID_MAX = 120) ---------- */
object Logx {

    /** Logical max width per line (requested 120 characters per line). */
    private const val ANDROID_MAX = 120

    /** Safety margin reserved for tag, level, and platform overhead. */
    private const val SAFE_HEADROOM = 16

    /** Maximum payload length per printed line (after safety margin). */
    private val MAX_CHUNK = max(32, ANDROID_MAX - SAFE_HEADROOM)

    /** Wrap width used for boxed body text (inner width without borders). */
    private val WRAP_WIDTH = max(20, MAX_CHUNK - 6)

    // ---------------------------------------------------------------------
    // Public API: plain messages
    // ---------------------------------------------------------------------

    fun v(tag: String, msg: String) = emit(Log.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = emit(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = emit(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = emit(Log.WARN, tag, msg)
    fun e(tag: String, msg: String) = emit(Log.ERROR, tag, msg)
    fun wtf(tag: String, msg: String) = emit(Log.ASSERT, tag, msg)

    // ---------------------------------------------------------------------
    // Public API: boxed helpers
    // ---------------------------------------------------------------------

    /**
     * Prints a multi-line message inside a simple box.
     *
     * Example:
     *  ╔═ TITLE
     *  ║ line 1
     *  ║ line 2
     *  ╚═ end
     */
    fun block(
        tag: String,
        title: String,
        body: String,
        priority: Int = Log.INFO,
    ) {
        if (!isLoggable(tag, priority)) return

        chunk(priority, tag, "╔═ $title")
        if (body.isNotEmpty()) {
            val norm = body.normalize()
            for (line in norm.split('\n')) {
                val wrapped = wrap(line, WRAP_WIDTH)
                for (w in wrapped) {
                    chunk(priority, tag, "║ $w")
                }
            }
        }
        chunk(priority, tag, "╚═ end")
    }

    /**
     * Formats key-value pairs and prints them in a box.
     *
     * Keys are aligned and values are wrapped within the box width.
     *
     * Accepts Map<String, *> so callers can pass maps with String, Int,
     * Boolean, collections, etc. without causing ClassCastException.
     */
    fun kv(
        tag: String,
        title: String,
        pairs: Map<String, *>,
        priority: Int = Log.INFO,
        nullText: String = "null",
    ) {
        if (!isLoggable(tag, priority)) return

        if (pairs.isEmpty()) {
            block(tag, title, "(empty)", priority)
            return
        }

        val keyWidth = pairs.keys.maxOf { it.length }
        val body = buildString {
            pairs.forEach { (k, rawAny) ->
                val vString = anyToString(rawAny, nullText).normalize()
                val head = "${k.padEnd(keyWidth)}: "
                val lines = wrap(vString, max(8, WRAP_WIDTH - head.length))
                if (lines.isEmpty()) {
                    appendLine(head)
                } else {
                    appendLine(head + lines.first())
                    for (i in 1 until lines.size) {
                        appendLine(" ".repeat(head.length) + lines[i])
                    }
                }
            }
        }.trimEnd()

        block(tag, title, body, priority)
    }

    /**
     * Prints an exception with an optional message inside a box.
     *
     * Includes full cause chain and stack traces.
     */
    fun ex(
        tag: String,
        title: String,
        throwable: Throwable,
        priority: Int = Log.ERROR,
        message: String? = null,
    ) {
        if (!isLoggable(tag, priority)) return

        val sb = StringBuilder()
        if (!message.isNullOrBlank()) {
            sb.appendLine(message.normalize())
            sb.appendLine()
        }
        sb.append(throwable.stackTraceString())
        block(tag, title, sb.toString(), priority)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Emits a message honoring the loggability for the given tag and level.
     *
     * Long messages are chunked to avoid hitting per-line limits.
     */
    private fun emit(priority: Int, tag: String, message: String) {
        if (!isLoggable(tag, priority)) return
        chunk(priority, tag, message)
    }

    /**
     * Chunks a single message into lines of at most [MAX_CHUNK] characters.
     *
     * This method does not insert any extra prefixes other than a small
     * trailing spacer to keep lines visually separated.
     */
    private fun chunk(priority: Int, tag: String, text: String) {
        val t = text.normalize()
        if (t.length <= MAX_CHUNK) {
            Log.println(priority, tag, "$t  ")
            return
        }

        var i = 0
        val n = t.length
        while (i < n) {
            val end = min(n, i + MAX_CHUNK)
            Log.println(priority, tag, "${t.substring(i, end)}  ")
            i = end
        }
    }

    /**
     * Wraps a single logical line to multiple lines, preferring breaks at
     * whitespace where possible, and falling back to hard breaks otherwise.
     */
    private fun wrap(line: String, width: Int): List<String> {
        if (line.isEmpty()) return listOf("")
        if (width <= 1 || line.length <= width) return listOf(line)

        val out = ArrayList<String>(max(1, line.length / width + 1))
        var i = 0
        val n = line.length

        while (i < n) {
            var end = min(n, i + width)
            if (end < n) {
                // Search backwards for whitespace between i and (end - 1).
                var j = end - 1
                while (j > i && line[j] != ' ') j--
                if (j > i) {
                    // Break just before the space; skip the space on the next line.
                    end = j
                }
            }
            val chunk = line.substring(i, end).trimEnd()
            out.add(chunk)

            // Next start position; skip a single space if present.
            i = if (end < n && line[end] == ' ') end + 1 else end
        }
        return out
    }

    /**
     * Normalizes line endings and removes trailing newlines.
     */
    private fun String.normalize(): String =
        this.replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    /**
     * Wrapper around [Log.isLoggable] that never throws and defaults to true
     * if the platform check itself fails.
     */
    @PublishedApi
    internal fun isLoggable(tag: String, priority: Int): Boolean =
        try {
            Log.isLoggable(tag, priority)
        } catch (_: Throwable) {
            true
        }

    /**
     * Builds a stack trace string including the full cause chain.
     */
    private fun Throwable.stackTraceString(): String =
        buildString {
            appendLine(this@stackTraceString.toString())
            this@stackTraceString.stackTrace.forEach { appendLine("    at $it") }
            var c = this@stackTraceString.cause
            while (c != null && c !== this@stackTraceString) {
                appendLine("Caused by: $c")
                c.stackTrace.forEach { appendLine("    at $it") }
                c = c.cause
            }
        }.trimEnd()

    /**
     * Converts arbitrary values to a human-readable String representation
     * suitable for logging.
     */
    private fun anyToString(value: Any?, nullText: String): String {
        if (value == null) return nullText

        return when (value) {
            is String -> value
            is CharSequence -> value.toString()
            is Throwable -> value.toString()
            is Boolean, is Number -> value.toString()
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]")
            is Map<*, *> -> value.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { "${it.key}=${it.value}" }
            else -> value.toString()
        }
    }
}
