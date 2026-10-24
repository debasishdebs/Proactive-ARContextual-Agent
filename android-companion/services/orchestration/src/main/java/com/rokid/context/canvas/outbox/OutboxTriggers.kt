package com.rokid.context.canvas.outbox

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import java.io.File

/**
 * The process-wide drainer (production wiring): the app database, the frame folders under `filesDir` as the only deletable phone roots, PcLog, and the glasses and helper ports that
 * are not reachable yet. Run by `MomentCadenceWorker` (the existing 15-minute cadence); there is no other trigger and no always-running service.
 */
object OutboxTriggers {
    @Volatile private var instance: OutboxDrainer? = null

    fun get(context: Context): OutboxDrainer = instance ?: synchronized(this) {
        instance ?: OutboxDrainer(
            store = RoomOutboxStore(MemoryGraph.get(context).db),
            files = PhoneFileCleanup(RokidFrameInbox.FRAME_DIRS.map { File(context.filesDir, it) }),
            glasses = NotReachableGlasses, helper = NotReachableHelper,
            log = PcLog.port("outbox"),
        ).also { instance = it }
    }

    fun resetForTests() { synchronized(this) { instance = null } }
}
