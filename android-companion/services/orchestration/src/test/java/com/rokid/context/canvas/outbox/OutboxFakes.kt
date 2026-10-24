package com.rokid.context.canvas.outbox

import com.rokid.context.core.log.Level
import com.rokid.context.core.log.LogPort
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget

/** In-memory ports for the drainer tests. */
class FakeStore(rows: List<OutboxRow> = emptyList()) : OutboxStore {
    val rows = rows.toMutableList()
    var attempts = HashMap<Long, OutboxAttempt>()
    var failRemoveOnce = false
    var removeCalls = 0
    override suspend fun pending(): List<OutboxRow> = rows.toList()
    override suspend fun remove(id: Long) {
        removeCalls++
        if (failRemoveOnce) { failRemoveOnce = false; throw IllegalStateException("crash before the row delete") }
        rows.removeAll { it.id == id }
    }
    override suspend fun attempts(): Map<Long, OutboxAttempt> = HashMap(attempts)
    override suspend fun saveAttempts(m: Map<Long, OutboxAttempt>) { attempts = HashMap(m) }
}

class FakeFiles(present: Collection<String> = emptyList()) : PhoneFilePort {
    val present = present.toMutableSet()
    val calls = ArrayList<String>()
    var failing = HashSet<String>()
    override suspend fun deleteConfirmed(path: String): CleanupResult {
        calls += path
        if (path in failing) return CleanupResult.FAILED
        present.remove(path); return CleanupResult.DONE
    }
}

class FakeGlasses(var reachable: Boolean = true) : GlassesCleanupPort {
    val calls = ArrayList<String>()
    var next: CleanupResult = CleanupResult.DONE
    var probes = 0
    override suspend fun reachable(): Boolean { probes++; return reachable }
    override suspend fun deleteFrame(wireFrameId: String) = next.also { calls += "frame" }
    override suspend fun deleteTrace(traceId: String) = next.also { calls += "trace" }
    override suspend fun deleteRange(fromMs: Long, toMs: Long) = next.also { calls += "range" }
    override suspend fun wipeAll() = next.also { calls += "wipe" }
}

class FakeHelper(var reachable: Boolean = true) : HelperCleanupPort {
    var calls = 0
    var next: CleanupResult = CleanupResult.DONE
    override suspend fun reachable() = reachable
    override suspend fun wipeAll() = next.also { calls++ }
}

class RecordingLog : LogPort {
    val lines = ArrayList<String>()
    override fun log(level: Level, tag: String, msg: String) { lines += "$tag $msg" }
}

fun phoneRow(id: Long, path: String) = OutboxRow(id, CleanupTarget.PHONE_FILE, CleanupPayload.one("path", path))
fun frameRow(id: Long, wire: String) = OutboxRow(id, CleanupTarget.GLASSES_FRAME, CleanupPayload.one("wireFrameId", wire))
fun traceRow(id: Long, trace: String) = OutboxRow(id, CleanupTarget.GLASSES_TRACE, CleanupPayload.one("traceId", trace))
fun rangeRow(id: Long, from: Long, to: Long) = OutboxRow(id, CleanupTarget.GLASSES_RANGE, CleanupPayload.range(from, to))
fun allRow(id: Long) = OutboxRow(id, CleanupTarget.ALL, "{\"reason\":\"delete_all\"}")
