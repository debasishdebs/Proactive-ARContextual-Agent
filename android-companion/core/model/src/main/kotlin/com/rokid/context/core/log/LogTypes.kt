package com.rokid.context.core.log

/** Severity, ordered D < I < W < E. */
enum class Level { D, I, W, E }

/**
 * One log record. [msg] is a constant template plus counts/enum names only (see [LogFields]).
 * [tag] and [msg] are cut to [MAX_TAG_BYTES] / [MAX_MSG_BYTES] of UTF-8 at construction, so every retained record
 * (ring, writer queue) and every written line has a hard byte bound.
 */
class Line(val level: Level, tag: String, msg: String, val wallMillis: Long) {
    val tag: String = LogBound.cut(tag, MAX_TAG_BYTES)
    val msg: String = LogBound.cut(msg, MAX_MSG_BYTES)

    companion object {
        const val MAX_TAG_BYTES = 64
        const val MAX_MSG_BYTES = 1024
    }
}

internal object LogBound {
    /** Cuts [s] to at most [maxBytes] of UTF-8 on a code point boundary (never splits a surrogate pair). */
    fun cut(s: String, maxBytes: Int): String {
        if (s.length * 3 <= maxBytes) return s
        var bytes = 0; var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > maxBytes) break
            bytes += n; i += Character.charCount(cp)
        }
        return if (i >= s.length) s else s.substring(0, i)
    }
}

/** What the ONE central decision ([LogPolicy.route]) says to do with a line. A sink executes it, never decides. */
data class Route(val logcat: Boolean, val file: Boolean)

/**
 * CALL-SIDE type: everything that logs holds a [LogPort] (production code, 3b via [PcLog.port]).
 * Deliberately unrelated to [LogSink], so nothing can be handed a backend and bypass the gate.
 */
fun interface LogPort { fun log(level: Level, tag: String, msg: String) }

/** BACKEND SPI, implemented only by the phone and glasses sinks and installed once via [PcLog.install]. Never throws is NOT assumed. */
interface LogSink { fun emit(line: Line, route: Route) }

/** File names under the app files dir (`files/logs/` on the phone). */
object LogFiles {
    const val PCA_LOG = "pca.log"
    const val QNA_EVAL = "qna_eval.jsonl"
}

/** Helpers that make the allowed dynamic shapes (counts, enum names, throwable class names) the easy path. */
object LogFields {
    fun count(n: Int): String = n.toString()
    fun count(n: Long): String = n.toString()
    fun enum(e: Enum<*>): String = e.name
    /** Class name only, never the message (it may hold user text, paths or provider bodies). */
    fun cls(t: Throwable): String =
        (t.javaClass.simpleName.ifEmpty { t.javaClass.name.substringAfterLast('.') }).take(64)
}
