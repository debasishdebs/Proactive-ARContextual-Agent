package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.log.PhoneLogging

/** What the native helper card shows. [available] false hides the card (a host that has not bound the helper yet). */
data class HelperCardState(
    val available: Boolean = false,
    val paired: Boolean = false,
    val helperName: String? = null,
    val grantPyExec: Boolean = false,
    /** The open pairing window's code, shown on the phone for the user to type on the laptop. */
    val pairingCode: String? = null,
    val pairingSecondsLeft: Int = 0,
) {
    companion object { val UNAVAILABLE = HelperCardState(available = false) }
}

/**
 * The native helper actions (core plan section 6, "Native actions"): accept a pairing, turn the `py_exec` grant on or off, disconnect. They call [HelperAuth] and
 * [HelperGrants] directly; there is no HTTP surface for any of them.
 */
interface HelperNativeActions {
    fun state(): HelperCardState
    /** Opens the pairing window (a fresh code, shown on the phone). Nothing pairs unless this was tapped. */
    fun acceptPairing()
    fun setGrant(grant: String, on: Boolean)
    /** Revokes the helper token, closes the pairing window and turns every grant off. */
    fun disconnectHelper()

    companion object {
        /** The inert default of a host that has not bound the helper: no card, no effect. */
        val UNAVAILABLE: HelperNativeActions = object : HelperNativeActions {
            override fun state() = HelperCardState.UNAVAILABLE
            override fun acceptPairing() {}
            override fun setGrant(grant: String, on: Boolean) {}
            override fun disconnectHelper() {}
        }
    }
}

class HelperNative(
    private val auth: HelperAuth,
    private val grants: HelperGrants,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: CanvasLogSink = PhoneLogging.canvasPort(),
) : HelperNativeActions {
    override fun state(): HelperCardState {
        val s = auth.session()
        val code = auth.pairingCode()
        return HelperCardState(
            available = true, paired = s != null, helperName = s?.name, grantPyExec = grants.isOn(HelperGrants.PY_EXEC),
            pairingCode = code?.code, pairingSecondsLeft = if (code != null) ((code.expiresAt - clock() + 999) / 1000).toInt().coerceAtLeast(0) else 0,
        )
    }

    override fun acceptPairing() {
        auth.acceptPairing()
        log.helperLine("helper pairing window open")
    }

    override fun setGrant(grant: String, on: Boolean) {
        if (grants.set(grant, on)) log.helperLine(if (on) "helper grant on" else "helper grant off")
    }

    override fun disconnectHelper() {
        auth.revoke()
        grants.allOff()
        log.helperLine("helper disconnect")
    }
}
