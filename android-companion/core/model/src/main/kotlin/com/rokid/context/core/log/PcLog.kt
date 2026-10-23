package com.rokid.context.core.log

/**
 * Process-wide logger, pure Kotlin (no Android). Call shape mirrors android.util.Log. Never throws into callers.
 * Before [install] (and in host unit tests) routed lines go to a bounded ring buffer that is replayed into the sink on install.
 */
object PcLog : LogPort {
    const val RING_CAPACITY = 256

    private class Installed(val sink: LogSink, val diag: () -> Boolean)

    private val lock = Any()
    @Volatile private var installed: Installed? = null
    private val ring = ArrayDeque<Pair<Line, Route>>()

    /** Installs the backend once; returns false (and changes nothing) if one is already installed. */
    fun install(sink: LogSink, diagnosticEnabled: () -> Boolean): Boolean {
        val replay: List<Pair<Line, Route>>
        synchronized(lock) {
            if (installed != null) return false
            installed = Installed(sink, diagnosticEnabled)
            replay = ring.toList(); ring.clear()
        }
        replay.forEach { (line, route) -> safeEmit(sink, line, route) }
        return true
    }

    /** An adapter that logs through [PcLog] (gated by construction). [area] only names the owner for readers of the code. */
    fun port(area: String): LogPort = AreaPort(area)

    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String) = log(Level.E, tag, msg)
    fun w(tag: String, msg: String, t: Throwable) = log(Level.W, tag, "$msg ${LogFields.cls(t)}")
    fun e(tag: String, msg: String, t: Throwable) = log(Level.E, tag, "$msg ${LogFields.cls(t)}")

    override fun log(level: Level, tag: String, msg: String) {
        try {
            val inst = installed
            val diag = if (inst == null) false else try { inst.diag() } catch (_: Throwable) { false }
            val route = LogPolicy.route(level, tag, diag)
            if (!route.logcat && !route.file) return          // disabled: no Line, no allocation beyond the call
            val line = Line(level, tag, msg, System.currentTimeMillis())
            if (inst != null) { safeEmit(inst.sink, line, route); return }
            synchronized(lock) {
                val now = installed
                if (now != null) { safeEmit(now.sink, line, route); return }
                if (ring.size >= RING_CAPACITY) ring.removeFirst()
                ring.addLast(line to route)
            }
        } catch (_: Throwable) { /* the logger never throws into its caller */ }
    }

    private fun safeEmit(sink: LogSink, line: Line, route: Route) {
        try { sink.emit(line, route) } catch (_: Throwable) { }
    }

    private class AreaPort(@Suppress("unused") val area: String) : LogPort {
        override fun log(level: Level, tag: String, msg: String) = PcLog.log(level, tag, msg)
    }

    internal fun resetForTest() { synchronized(lock) { installed = null; ring.clear() } }
    internal fun bufferedForTest(): List<Line> = synchronized(lock) { ring.map { it.first } }
}
