package com.rokid.context.memory.place

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Thrown (as a cancellation) when the [ZoneFence] closed under a running Place Guide attempt: nothing further is read, fetched, sent or written. */
class ZoneFenced : CancellationException("zone fence closed")

/**
 * THE zone fence of Place Guide: every side effect (a photo upload, a place-pack fetch and each request in it, a memory write, a card) asks it, and it is the only thing that does.
 *
 * - **Held** ([hold]/[release]): the zone controller holds it from the moment ANY zone is approached, entered, recorded or unreadable, until it is clear.
 * - **Tripped** ([trip]): the Place Guide switch, Delete all and Forget end every attempt in flight without holding anything.
 * - Both advance the epoch and CANCEL the running attempts' job at once, so a suspended fetch, upload or database read stops where it is (a network request is disconnected).
 * - Check-then-act: [act] runs its block under the fence's lock with the check immediately before it, and [hold]/[trip] take the same lock, so a withdrawal never overlaps an action. A
 *   suspending dispatch calls [ZoneTicket.check] as its last step before the call, with no suspension in between; the call itself is cancelled by the job if the fence closes after.
 */
class ZoneFence(
    /** A synchronous second opinion read at every check (production: the controller's `approach || enforcing`). */
    @Volatile var extraBlocked: () -> Boolean = { false },
) {
    private val lock = Any()
    private var epochValue = 0L
    private var heldValue = false
    private var job: CompletableJob = Job()
    private val closeHooks = LinkedHashSet<() -> Unit>()

    val held: Boolean get() = synchronized(lock) { heldValue }
    fun epoch(): Long = synchronized(lock) { epochValue }

    /** True while an attempt begun at [epoch] may still act. */
    fun open(epoch: Long): Boolean = synchronized(lock) { openLocked(epoch) }

    private fun openLocked(epoch: Long) = !heldValue && epochValue == epoch && !extraBlocked()

    /** A ticket for an attempt that starts now. A ticket taken while the fence is held is already closed. */
    fun ticket(): ZoneTicket = synchronized(lock) { ZoneTicket(this, epochValue) }

    /** Launches [block] in the current attempt job (cancelled by [hold] and [trip]) with its ticket in the context. */
    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job = synchronized(lock) {
        scope.launch(job + ZoneTicket(this, epochValue), block = block)
    }

    /** Runs [block] only if [ticket] is still open, under the lock; null = not run. [block] must not suspend. */
    fun <T : Any> act(ticket: ZoneTicket, block: () -> T): T? = synchronized(lock) { if (ticket.fence === this && openLocked(ticket.epoch)) block() else null }

    /**
     * Registers [hook] to run (synchronously, on the closing thread, outside the lock) when the fence closes, but only if [ticket] is still open: the check and the registration are one step
     * under the lock, so a close either precedes it (throws [ZoneFenced], nothing registered) or sees the hook. Close the returned handle when the dispatch is over.
     */
    fun onClose(ticket: ZoneTicket, hook: () -> Unit): AutoCloseable {
        synchronized(lock) {
            if (ticket.fence !== this || !openLocked(ticket.epoch)) throw ZoneFenced()
            closeHooks.add(hook)
        }
        return AutoCloseable { synchronized(lock) { closeHooks.remove(hook) } }
    }

    /** A zone is approached, entered, recorded or unreadable: nothing runs until [release], and everything in flight is cancelled now. */
    fun hold() { close(true) }

    /** The zone controller says no zone is approached, entered, recorded or unreadable any more. Attempts begun before stay closed (the epoch moved). */
    fun release() { synchronized(lock) { heldValue = false } }

    /** The switch went off, or Delete all or a Forget ran: attempts in flight end; new ones are allowed. */
    fun trip() { close(false) }

    private fun close(hold: Boolean) {
        val old: CompletableJob
        val hooks: List<() -> Unit>
        synchronized(lock) {
            hooks = closeHooks.toList(); closeHooks.clear()
            if (hold) heldValue = true
            epochValue++
            old = job
            job = Job()
        }
        hooks.forEach { runCatching(it) }
        old.cancel(ZoneFenced())
    }
}

/** An attempt's pass through the [ZoneFence]: valid until the fence closes. Travels in the coroutine context so deep layers (the pack, the http client) can ask without a new parameter. */
class ZoneTicket(val fence: ZoneFence, val epoch: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ZoneTicket>

    fun isOpen(): Boolean = fence.open(epoch)

    /** [ZoneFence.onClose] for this ticket: the registration is the admission check. */
    fun onClose(hook: () -> Unit): AutoCloseable = fence.onClose(this, hook)

    /** Throws [ZoneFenced] unless open. Call as the last step before a dispatch, with no suspension between the call and the action. */
    fun check() { if (!isOpen()) throw ZoneFenced() }
}

/** The ticket of the running attempt, or null (an unfenced caller: seeding, tests of the pure parts). */
suspend fun currentZoneTicket(): ZoneTicket? = coroutineContext[ZoneTicket]

/** [ZoneTicket.check] for whoever runs inside an attempt; no-op outside one. Does not suspend. */
suspend fun zoneCheck() { coroutineContext[ZoneTicket]?.check() }
