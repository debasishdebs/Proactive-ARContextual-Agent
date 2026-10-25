package com.rokid.context.canvas

import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.orchestration.DataOpGate
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** Watch-off: the run rows that hold watch values go. A delete, so it takes the [DataOpGate] (waits for a running export, else throws [DataOpGate.ExportInProgress]; the rows stay). */
object WatchRowCleanup {
    suspend fun deleteWatchRuns(dao: CanvasDao): Int = DataOpGate.shared.delete { dao.deleteWatchRuns() }

    private val pending = AtomicBoolean(false)

    /**
     * The switch-off cleanup that cannot be lost: when an export holds the gate for longer than the delete's wait, it tries again every [retryMs] until the rows are gone
     * (cancelled only with its scope). One cleanup at a time: a second switch-off while one is pending is already covered by it (it deletes every watch row when it runs).
     */
    suspend fun deleteUntilDone(dao: CanvasDao, retryMs: Long = 30_000L) {
        if (!pending.compareAndSet(false, true)) return
        try {
            while (true) {
                try { deleteWatchRuns(dao); return } catch (e: DataOpGate.ExportInProgress) { delay(retryMs) }
            }
        } finally { pending.set(false) }
    }
}
