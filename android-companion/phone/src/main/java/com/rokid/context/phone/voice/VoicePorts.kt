package com.rokid.context.phone.voice

import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Home's phone-mic Ask (spec §2.4, §5.1 step 4). */
interface PhoneVoice {
    val state: StateFlow<PhoneVoiceState>
    fun start()
    fun stop()
    fun cancel()

    companion object {
        val None: PhoneVoice = object : PhoneVoice {
            override val state: StateFlow<PhoneVoiceState> = MutableStateFlow(PhoneVoiceState(status = VoiceStatus.NO_ON_DEVICE_RECOGNIZER))
            override fun start() = Unit
            override fun stop() = Unit
            override fun cancel() = Unit
        }
    }
}

enum class PackDownload { DONE, SCHEDULED, FAILED, NEEDS_SETTINGS }

/** Settings › Devices › Phone camera & mic (spec §5.1.1 unavailable UX). */
interface VoiceControls {
    val capabilities: StateFlow<VoiceCapabilities>
    val settings: StateFlow<VoiceSettings>
    fun setLanguage(language: SpeechLanguage)
    fun setPhoneMic(on: Boolean)
    /** Re-reads the gate and probes external audio when needed: call it only while the app is in front. */
    fun recheck()
    suspend fun downloadPack(): PackDownload
    fun openSpeechSettings()

    companion object {
        val None: VoiceControls = object : VoiceControls {
            override val capabilities: StateFlow<VoiceCapabilities> = MutableStateFlow(VoiceCapabilities.UNKNOWN)
            override val settings: StateFlow<VoiceSettings> = MutableStateFlow(VoiceSettings())
            override fun setLanguage(language: SpeechLanguage) = Unit
            override fun setPhoneMic(on: Boolean) = Unit
            override fun recheck() = Unit
            override suspend fun downloadPack() = PackDownload.FAILED
            override fun openSpeechSettings() = Unit
        }
    }
}
