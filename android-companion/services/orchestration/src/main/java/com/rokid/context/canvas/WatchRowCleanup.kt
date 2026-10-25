package com.rokid.context.canvas

import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.orchestration.DataOpGate

/** Watch-off: the run rows that hold watch values go. A delete, so it takes the [DataOpGate] (waits for a running export, else throws [DataOpGate.ExportInProgress]; the rows stay). */
object WatchRowCleanup {
    suspend fun deleteWatchRuns(dao: CanvasDao): Int = DataOpGate.shared.delete { dao.deleteWatchRuns() }
}
