package com.rokid.context.phone.ui.home

import com.rokid.context.phone.voice.PhoneVoice
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakePhoneVoice(initial: PhoneVoiceState = PhoneVoiceState(status = VoiceStatus.SUPPORTED)) : PhoneVoice {
    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    override fun start() { calls += "start"; state.value = state.value.copy(listening = true) }
    override fun stop() { calls += "stop"; state.value = state.value.copy(listening = false, thinking = true) }
    override fun cancel() { calls += "cancel" }
}
