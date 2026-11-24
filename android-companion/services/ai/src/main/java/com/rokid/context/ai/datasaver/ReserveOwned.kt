package com.rokid.context.ai.datasaver

import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataReservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Codex r4 P2-4: the durable reservation runs on an IO thread. A coroutine cancelled while that block runs would otherwise discard the token the block just committed (withContext throws instead of
 * returning it), leaving a call that never left charged until the day rolls over. So ownership is captured INSIDE the IO block, and a cancellation releases it (settle with zeros) in a NonCancellable cleanup.
 * A failure of the gate itself is reported through [onError] (a fixed token only) and answers [DataReservation.FAILED]: fail closed, no durable count, no costly send.
 */
internal suspend fun reserveOwned(gate: DataGate, kind: DataKind, tx: Long, photos: Int, onError: (Throwable) -> Unit = {}): DataReservation? {
    val owned = AtomicReference<DataReservation?>(null)
    try {
        return withContext(Dispatchers.IO) {
            val r = try { gate.reserve(kind, tx, photos) } catch (c: CancellationException) { throw c } catch (t: Throwable) { onError(t); DataReservation.FAILED }
            if (r != null && !r.failed) owned.set(r)
            r
        }
    } catch (c: CancellationException) {
        // withContext returns only after its block has ended, so [owned] is final here. Nothing was sent: give the count back.
        val r = owned.getAndSet(null)
        if (r != null) withContext(NonCancellable + Dispatchers.IO) { try { gate.settle(r, 0, 0, 0) } catch (_: Throwable) { /* the reservation stays counted */ } }
        throw c
    }
}
