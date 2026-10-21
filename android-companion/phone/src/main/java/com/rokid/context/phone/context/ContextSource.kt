package com.rokid.context.phone.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** One opt-in signal source (a thin Android shell over a pure core). The hub starts and stops it. */
interface ContextSource {
    /** One of the six source ids, or the internal `phone_airplane`. */
    val id: String
    /** Hardware / Play services / app present; false means the hub never starts it. */
    fun available(): Boolean
    /**
     * false = could not start (permission, missing sensor). Take `hub.sinkFor(id)` inside this call and use it for this run only:
     * evidence sent during `start()` is accepted and a callback from an earlier run is dropped.
     */
    fun start(): Boolean
    fun stop()
    val events: Int
}

/** The speed adapter's on/off (Task 4); the hub asks for it only while a vehicle is suspected. */
fun interface SpeedControl {
    fun setWanted(on: Boolean)
    /** True while location updates are actually requested (the hub's `locOnS` counts this, not "wanted"). */
    val requesting: Boolean get() = false
}

/** UNAVAILABLE = switched on but not registered, or `available()` is false. */
enum class SourceStatus { OFF, ON, UNAVAILABLE, FAILED }

/** Sources register themselves here (each later task adds its own); the hub reconciles against it. */
class ContextSourceRegistry {
    private val sources = ConcurrentHashMap<String, ContextSource>()
    private val _changes = MutableStateFlow(0)

    fun register(s: ContextSource) { sources[s.id] = s; _changes.update { it + 1 } }
    fun get(id: String): ContextSource? = sources[id]
    val ids: Set<String> get() = sources.keys.toSet()

    /** null until Task 4: the hub's `setSpeedWanted` is then a no-op. */
    @Volatile var speed: SpeedControl? = null

    /** Bumped on every [register]. */
    val changes: StateFlow<Int> = _changes
}
