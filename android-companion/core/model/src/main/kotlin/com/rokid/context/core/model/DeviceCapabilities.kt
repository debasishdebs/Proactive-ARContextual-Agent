package com.rokid.context.core.model

/** One editable capability of one device: [device] `phone|glasses`, [capability] `mic|display`. [constraint] is a fixed token (only `hud` on the glasses display: that display IS the HUD overlay). */
data class DeviceCapability(val device: String, val capability: String, val enabled: Boolean, val constraint: String? = null)

/**
 * v0.9.8 slice D: which microphones and displays the USER lets flows use. Four fixed entries, default = all enabled (today's behaviour). This is a user setting, never what the hardware reports,
 * and it never feeds the classifier, capture or the plan hash. A disabled display makes that delivery target unavailable ([DeliveryRoute] falls back with a recorded reason); a disabled mic
 * makes that voice surface ignore its utterances with a truthful reply.
 */
data class DeviceCapabilities(val disabled: Set<String> = emptySet()) {
    fun enabled(device: String, capability: String): Boolean = key(device, capability) !in disabled

    /** Unknown device or capability: unchanged (never stored). */
    fun with(device: String, capability: String, on: Boolean): DeviceCapabilities {
        val k = key(device, capability)
        if (k !in KEYS) return this
        return DeviceCapabilities(if (on) disabled - k else disabled + k)
    }

    fun list(): List<DeviceCapability> = CATALOGUE.map { (d, c) -> DeviceCapability(d, c, enabled(d, c), if (d == GLASSES && c == DISPLAY) CONSTRAINT_HUD else null) }

    val phoneDisplay: Boolean get() = enabled(PHONE, DISPLAY)
    val glassesDisplay: Boolean get() = enabled(GLASSES, DISPLAY)

    /** The mic of the voice surface that listens: the glasses' or the phone's. */
    fun micOn(glasses: Boolean): Boolean = enabled(if (glasses) GLASSES else PHONE, MIC)

    /**
     * A publish warning (never a block) when a flow's only delivery [target] is switched off: `phone` with the phone display off, `glasses` with the glasses display off, `both` with both off.
     * `caller` is the asker's own surface and never warns. Null when the target works.
     */
    fun deliveryWarning(target: String): String? = when (target) {
        "phone" -> if (!phoneDisplay) "Delivery target phone: the phone display is switched off in Settings, so answers will fall back" else null
        "glasses" -> if (!glassesDisplay) "Delivery target glasses: the glasses display is switched off in Settings, so answers will fall back" else null
        "both" -> if (!phoneDisplay && !glassesDisplay) "Delivery target both: the phone and glasses displays are switched off in Settings, so answers will fall back" else null
        else -> null
    }

    companion object {
        const val PHONE = "phone"
        const val GLASSES = "glasses"
        const val MIC = "mic"
        const val DISPLAY = "display"
        const val CONSTRAINT_HUD = "hud"
        const val PHONE_DISPLAY_OFF = "phone_display_off"
        const val GLASSES_DISPLAY_OFF = "glasses_display_off"
        const val NO_DISPLAY = "no_display_enabled"
        val CATALOGUE: List<Pair<String, String>> = listOf(PHONE to MIC, PHONE to DISPLAY, GLASSES to MIC, GLASSES to DISPLAY)
        private val KEYS = CATALOGUE.map { key(it.first, it.second) }.toSet()
        val ALL = DeviceCapabilities()
        fun key(device: String, capability: String) = "$device.$capability"
        fun known(device: String, capability: String) = key(device, capability) in KEYS

        /** The stored keys that are in the catalogue (anything else is dropped at load). */
        fun clean(keys: Collection<String>) = DeviceCapabilities(keys.filter { it in KEYS }.toSet())
    }
}
