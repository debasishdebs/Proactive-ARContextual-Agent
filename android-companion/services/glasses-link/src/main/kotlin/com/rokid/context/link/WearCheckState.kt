package com.rokid.context.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.0 wear fallback (Codex r1 finding 3): the glasses' system wear check as read in the CURRENT link session. [value] is null until a read of this session
 * (every foreground session open and every background bind re-reads it; a failed read is null again). The value kept in the prefs ([persisted]) is a display
 * and log hint only: it never feeds [value], so a stale "off" can never permit an estimate before the current session re-validates it.
 */
internal class WearCheckState(
    /** The last non-null read of any earlier session (prefs); display/log only. */
    persisted: Boolean?,
    private val persist: (Boolean) -> Unit,
) {
    var persisted: Boolean? = persisted
        private set
    private val _value = MutableStateFlow<Boolean?>(null)
    val value: StateFlow<Boolean?> = _value

    /** A fresh read ([on] null = the read failed or the link was not bound: unknown). */
    fun onRead(on: Boolean?) {
        _value.value = on
        if (on != null && on != persisted) { persisted = on; persist(on) }
    }

    /** A session is opening and nothing can be read yet: the value of an earlier session is dropped. */
    fun invalidate() { _value.value = null }
}
