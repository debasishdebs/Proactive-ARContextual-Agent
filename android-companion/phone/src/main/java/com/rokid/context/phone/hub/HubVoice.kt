package com.rokid.context.phone.hub

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Voice as the hub sees it (spec §1.7): glasses voice events in; the HUD overlay and the ask gate's reason out. */
interface HubVoice {
    val overlay: StateFlow<Overlay?>
    val askBlockedReason: StateFlow<String?>
    fun onListenStart()
    fun onAudio(pcm: ByteArray)
    fun onListenEnd()
    fun onCancel()
    fun onLinkLost()

    companion object {
        /** No voice (tests and builds without it): no overlay, nothing blocked, events ignored. */
        val None: HubVoice = object : HubVoice {
            override val overlay: StateFlow<Overlay?> = MutableStateFlow(null)
            override val askBlockedReason: StateFlow<String?> = MutableStateFlow(null)
            override fun onListenStart() = Unit
            override fun onAudio(pcm: ByteArray) = Unit
            override fun onListenEnd() = Unit
            override fun onCancel() = Unit
            override fun onLinkLost() = Unit
        }
    }
}
