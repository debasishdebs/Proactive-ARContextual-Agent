package com.rokid.context.voice

enum class VoiceStatus { SUPPORTED, CHECKING, NOT_VERIFIED, NEEDS_MIC_PERMISSION, NEEDS_SPEECH_PACK, NEEDS_NEWER_ANDROID, NO_ON_DEVICE_RECOGNIZER, EXTERNAL_AUDIO_UNSUPPORTED }

/** What the gate reads. [packInstalled] null = still checking; [probe] null = never verified for this OS/app/language. */
data class VoiceInputs(
    val sdk: Int,
    val onDeviceAvailable: Boolean,
    val micGranted: Boolean,
    val packInstalled: Boolean?,
    val probe: ProbeVerdict?,
    val probing: Boolean,
)

/** `GLASSES_VOICE` and `PHONE_VOICE` (spec §5.1.1) as statuses the UI can explain. */
data class VoiceCapabilities(val glasses: VoiceStatus, val phone: VoiceStatus, val canDownloadPack: Boolean) {
    /** `HudSettings.askBlockedReason`: the HUD toast for tap = Ask, null when glasses voice works. */
    val askBlockedReason: String? get() = VoiceStatusText.hudReason(glasses)

    companion object { val UNKNOWN = VoiceCapabilities(VoiceStatus.CHECKING, VoiceStatus.CHECKING, canDownloadPack = false) }
}

object VoiceGate {
    /** `RecognizerIntent.EXTRA_AUDIO_SOURCE`. */
    const val GLASSES_MIN_SDK = 33
    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer`. */
    const val PHONE_MIN_SDK = 31
    /** `SpeechRecognizer.triggerModelDownload` with a listener (Live Fit `SpeechPacks`); below it: system settings. */
    const val PACK_DOWNLOAD_MIN_SDK = 34

    fun decide(i: VoiceInputs): VoiceCapabilities {
        val phone = when {
            i.sdk < PHONE_MIN_SDK -> VoiceStatus.NEEDS_NEWER_ANDROID
            !i.onDeviceAvailable -> VoiceStatus.NO_ON_DEVICE_RECOGNIZER
            !i.micGranted -> VoiceStatus.NEEDS_MIC_PERMISSION
            i.packInstalled == null -> VoiceStatus.CHECKING
            !i.packInstalled -> VoiceStatus.NEEDS_SPEECH_PACK
            else -> VoiceStatus.SUPPORTED
        }
        val glasses = when {
            i.sdk < GLASSES_MIN_SDK -> VoiceStatus.NEEDS_NEWER_ANDROID
            phone != VoiceStatus.SUPPORTED -> phone
            i.probing -> VoiceStatus.CHECKING
            i.probe == null -> VoiceStatus.NOT_VERIFIED
            i.probe == ProbeVerdict.SUPPORTED -> VoiceStatus.SUPPORTED
            else -> VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED
        }
        return VoiceCapabilities(glasses, phone, canDownloadPack = i.sdk >= PACK_DOWNLOAD_MIN_SDK)
    }
}

/** Installed on-device language packs are BCP-47 tags; an exact tag wins, else the same language counts (ruling). */
object PackMatch {
    fun installed(tag: String, packs: Set<String>): Boolean =
        packs.any { it.equals(tag, ignoreCase = true) } ||
            packs.any { it.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }
}

/** Settings rows (spec §5.1.1: Supported / Needs speech pack / Needs Android 13+ for glasses voice) and HUD reasons. */
object VoiceStatusText {
    /** Shared with the voice pipeline's HUD toasts (preflight F13: `VoiceCopy` refers to these, never repeats them). */
    const val NOT_SUPPORTED_HUD = "Voice not supported on this phone"
    const val PACK_HUD = "Download speech pack on phone"
    const val MIC_HUD = "Allow the microphone on phone"

    fun glasses(s: VoiceStatus): String = when (s) {
        VoiceStatus.NEEDS_NEWER_ANDROID -> "Needs Android 13+ for glasses voice"
        else -> common(s)
    }

    fun phone(s: VoiceStatus): String = when (s) {
        VoiceStatus.NEEDS_NEWER_ANDROID -> "Needs Android 12+"
        else -> common(s)
    }

    private fun common(s: VoiceStatus): String = when (s) {
        VoiceStatus.SUPPORTED -> "Supported"
        VoiceStatus.CHECKING -> "Checking…"
        VoiceStatus.NOT_VERIFIED -> "Not checked yet"
        VoiceStatus.NEEDS_MIC_PERMISSION -> "Needs microphone permission"
        VoiceStatus.NEEDS_SPEECH_PACK -> "Needs speech pack"
        VoiceStatus.NEEDS_NEWER_ANDROID -> "Needs a newer Android"
        VoiceStatus.NO_ON_DEVICE_RECOGNIZER, VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED -> "Not supported on this phone"
    }

    /** The glasses toast for tap = Ask (≤ 60 characters), null when glasses voice works. */
    fun hudReason(s: VoiceStatus): String? = when (s) {
        VoiceStatus.SUPPORTED -> null
        VoiceStatus.CHECKING -> "Checking voice on phone…"
        VoiceStatus.NOT_VERIFIED -> "Open Proactive Context on phone to set up voice"
        VoiceStatus.NEEDS_MIC_PERMISSION -> MIC_HUD
        VoiceStatus.NEEDS_SPEECH_PACK -> PACK_HUD
        VoiceStatus.NEEDS_NEWER_ANDROID, VoiceStatus.NO_ON_DEVICE_RECOGNIZER, VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED -> NOT_SUPPORTED_HUD
    }
}
