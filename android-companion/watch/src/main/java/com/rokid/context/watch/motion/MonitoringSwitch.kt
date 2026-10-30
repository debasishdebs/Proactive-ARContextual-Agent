package com.rokid.context.watch.motion

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Health Services passive monitoring, reduced to what the switch needs. */
interface MonitoringClient {
    suspend fun register()
    suspend fun unregister()
}

/**
 * Serialises register / unregister / tick alarm so the last wish wins: rapid off/on always ends registered with the alarm armed, and off always ends unregistered
 * with the alarm cancelled. Every operation runs under one lock and re-reads [want] afterwards, looping until what it did still matches the latest wish.
 */
class MonitoringSwitch(
    private val client: MonitoringClient,
    private val scheduleTick: () -> Unit,
    private val cancelTick: () -> Unit,
    /** The current wish: sharing enabled and permitted. */
    private val want: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    @Volatile var registered = false; private set
    @Volatile var armed = false; private set

    suspend fun reconcile() = lock.withLock {
        while (true) {
            if (want()) {
                if (!registered) {
                    try { client.register(); registered = true } catch (c: CancellationException) { throw c } catch (e: Exception) { log("register failed ${e.javaClass.simpleName}"); return }
                }
                if (!armed && want()) { scheduleTick(); armed = true }
                if (want()) return
            } else {
                cancelTick(); armed = false
                // Always ask Health Services (its registration outlives this process), and only then forget it.
                try { client.unregister() } catch (c: CancellationException) { throw c } catch (e: Exception) { log("unregister failed ${e.javaClass.simpleName}") }
                registered = false
                if (!want()) return
            }
        }
    }
}
