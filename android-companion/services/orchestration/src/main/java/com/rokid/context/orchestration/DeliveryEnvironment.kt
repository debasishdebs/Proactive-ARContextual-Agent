package com.rokid.context.orchestration

import com.rokid.context.core.model.DeviceCapabilities
import com.rokid.context.core.model.GlassesReach

/**
 * v0.9.8 slice C: what the delivery routing may know about the glasses. Set by the phone hub (HubGraph) once it exists; until then (or in a process without a hub, such as a
 * background worker that started first) the glasses count as not connected, so a glasses target falls back to the phone with its reason recorded: never a silent loss.
 */
object DeliveryEnvironment {
    @Volatile var glassesConnected: (() -> Boolean)? = null

    /** v0.9.8 slice D: the user's device capabilities (set once by `ContextApp`, so a background worker sees them too). Unset = everything on, today's behaviour. */
    @Volatile var capabilities: (() -> DeviceCapabilities)? = null

    fun caps(): DeviceCapabilities = try { capabilities?.invoke() } catch (_: Exception) { null } ?: DeviceCapabilities.ALL

    fun reach(): GlassesReach = if (try { glassesConnected?.invoke() } catch (_: Exception) { null } == true) GlassesReach.UP else GlassesReach.DOWN
}
