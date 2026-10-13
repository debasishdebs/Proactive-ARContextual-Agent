package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.render.HudPresenter.Companion.isAskFailure
import com.rokid.context.core.model.Overlay

/**
 * Final review I2: while the glasses record, a phone toast (not an undo) that was not already showing when the ask
 * started means the phone refused or ended this ask (busy, recognizer failed, pack missing, fallback, blocked). The
 * caller then stops push-to-talk with no Cancel (the phone already dropped the ask) so the toast shows. Main thread only.
 */
class PhoneAskWatch {
    private var armed = false
    private var sawRecording = false
    private var last: Overlay? = null

    /** Just before push-to-talk starts, with the phone overlay shown now (a toast already there is not this ask's). */
    fun onStart(phone: Overlay?) { armed = true; sawRecording = false; last = phone }

    /** True once per ask: stop recording now. */
    fun shouldEnd(recording: Boolean, phone: Overlay?): Boolean {
        if (!armed) return false
        if (!recording) { if (sawRecording) armed = false; return false }
        sawRecording = true
        if (phone == last) return false
        last = phone
        if (!phone.isAskFailure()) return false
        armed = false
        return true
    }
}
