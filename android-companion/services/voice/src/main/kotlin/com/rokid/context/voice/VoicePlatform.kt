package com.rokid.context.voice

/** The platform facts the gate needs (Android: `AndroidVoicePlatform`; a fake in tests). Called on a worker thread. */
interface VoicePlatform {
    val sdk: Int
    fun onDeviceAvailable(): Boolean
    fun micGranted(): Boolean
    /** Installed on-device language packs, or null when the platform can't say (API < 33, or the query failed). */
    suspend fun installedPacks(): Set<String>?
    /** One external-audio probe run for [localeTag], or null when it couldn't run. */
    suspend fun runProbe(localeTag: String): ProbeOutcome?
    /** Changes with an OS or app update (spec §5.1.1: re-probe after updates). */
    fun fingerprint(): String
}
