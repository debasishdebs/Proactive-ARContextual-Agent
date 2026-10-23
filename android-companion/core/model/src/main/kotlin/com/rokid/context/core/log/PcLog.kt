package com.rokid.context.core.log

/**
 * Process-wide logger, pure Kotlin (no Android). Call shape mirrors android.util.Log. Never throws into callers.
 *
 * Diagnostic state is a volatile snapshot ([setDiagnostic]) written OUTSIDE the logging path (persisted-flag IO belongs to
 * the owner of the flag); the logging path only reads it, so a blocked flag loader can never block a call, even W/E.
 *
 * Bootstrap contract: before [install] completes, RAW records (every level, bounded by [Line]) go to a ring of the last
 * [RING_CAPACITY] records. [install] replays them in order, routing each with the diagnostic snapshot at replay time
 * (so a D or I record logged before install is kept if diagnostics are on by then), and publishes the sink only once the
 * ring is empty: live calls made during replay are buffered and replayed after, so live lines never overtake early ones.
 */
object PcLog : LogPort {
    const val RING_CAPACITY = 256

    private val lock = Any()
    @Volatile private var sink: LogSink? = null
    @Volatile private var diagnostic = false
    private var installing = false
    private val ring = ArrayDeque<Line>()

    fun setDiagnostic(enabled: Boolean) { diagnostic = enabled }

    /** Installs the backend once; returns false (and changes nothing) if one is installed or being installed. */
    fun install(backend: LogSink): Boolean {
        synchronized(lock) { if (sink != null || installing) return false; installing = true }
        while (true) {
            val batch = synchronized(lock) {
                if (ring.isEmpty()) { sink = backend; installing = false; return true }
                ring.toList().also { ring.clear() }
            }
            batch.forEach { emitRouted(backend, it) }
        }
    }

    /** An adapter that logs through [PcLog] (gated by construction). [area] only names the owner for readers of the code. */
    fun port(area: String): LogPort = AreaPort(area)

    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String) = log(Level.E, tag, msg)
    fun w(tag: String, msg: String, t: Throwable) = log(Level.W, tag, withClass(msg, t))
    fun e(tag: String, msg: String, t: Throwable) = log(Level.E, tag, withClass(msg, t))

    private fun withClass(msg: String, t: Throwable) =
        LogBound.cut(msg, Line.MAX_MSG_BYTES - 80) + " " + LogFields.cls(t)

    override fun log(level: Level, tag: String, msg: String) {
        try {
            val s = sink
            if (s != null) {
                val route = LogPolicy.route(level, tag, diagnostic)
                if (!route.logcat && !route.file) return          // disabled: no Line allocated
                safeEmit(s, Line(level, tag, msg, System.currentTimeMillis()), route)
                return
            }
            val line = Line(level, tag, msg, System.currentTimeMillis())
            val now = synchronized(lock) {
                val n = sink
                if (n == null) { if (ring.size >= RING_CAPACITY) ring.removeFirst(); ring.addLast(line) }
                n
            }
            if (now != null) emitRouted(now, line)
        } catch (_: Throwable) { /* the logger never throws into its caller */ }
    }

    private fun emitRouted(s: LogSink, line: Line) {
        val route = LogPolicy.route(line.level, line.tag, diagnostic)
        if (route.logcat || route.file) safeEmit(s, line, route)
    }

    private fun safeEmit(s: LogSink, line: Line, route: Route) {
        try { s.emit(line, route) } catch (_: Throwable) { }
    }

    private class AreaPort(@Suppress("unused") val area: String) : LogPort {
        override fun log(level: Level, tag: String, msg: String) = PcLog.log(level, tag, msg)
    }

    /**
     * TEST SEAM ONLY, public so tests of other modules can isolate process state: drops the sink, the ring and the diagnostic snapshot.
     * Production code must NEVER call it (a phone test scans every main source for this name).
     */
    fun testOnlyResetNeverCallFromProduction() { synchronized(lock) { sink = null; installing = false; ring.clear(); diagnostic = false } }
    internal fun bufferedForTest(): List<Line> = synchronized(lock) { ring.toList() }
}
