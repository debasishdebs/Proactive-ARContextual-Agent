package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/** Test double: records what the hub sends and lets a test play glasses messages. */
class FakeGlassesLink(initial: LinkState = LinkState.CONNECTED) : GlassesLink {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LinkState> = _state
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GlassesEvent> = _events
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val ready: SharedFlow<Unit> = _ready
    val sent: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    var connectCalls = 0
        private set
    var connectOnceCalls = 0
        private set

    fun setState(s: LinkState) { _state.value = s }
    suspend fun emit(e: GlassesEvent) = _events.emit(e)
    fun emitReady() { _ready.tryEmit(Unit) }

    override fun connect() { connectCalls++ }
    override fun connectOnce() { connectOnceCalls++ }
    override fun connectOnResume() = Unit
    override suspend fun send(channel: String, json: String): Boolean {
        if (_state.value != LinkState.CONNECTED) return false
        sent += channel to json
        return true
    }
    override fun close() = Unit
}
