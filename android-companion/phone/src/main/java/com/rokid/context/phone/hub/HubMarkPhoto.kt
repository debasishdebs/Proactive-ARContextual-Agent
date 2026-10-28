package com.rokid.context.phone.hub

import com.rokid.context.canvas.marks.PhonePhotoRequester
import com.rokid.context.core.model.MarkPhotoReason

/**
 * v0.9.7 slice D: the photo decision of a PHONE-origin mark (voice on the phone, or the Home chat), from the live state and nothing else (pure; the Hub and the call guard are passed as lambdas).
 * Order: link down -> `glasses_not_connected`; this glasses build never says it can ([supportsRequest] false) -> `no_photo_support`; a call is active -> `camera_busy` (the Rokid call owns the camera);
 * worn false -> `glasses_not_worn`; worn UNKNOWN is treated as worn only while capture is running -> else `glasses_not_worn`. Null = ask. The photo itself then follows the tap-mark path on the glasses
 * (explicit camera open, hold, the bounded pause exception, one photo per mark, the same reasons and the phone's timeout).
 */
class HubMarkPhoto(
    private val connected: () -> Boolean,
    private val supportsRequest: () -> Boolean,
    private val worn: () -> Boolean?,
    private val captureRunning: () -> Boolean,
    private val callActive: () -> Boolean,
    private val send: suspend (markRef: String) -> Boolean,
) : PhonePhotoRequester {
    override fun reasonNow(): String? = when {
        !connected() -> MarkPhotoReason.GLASSES_NOT_CONNECTED
        !supportsRequest() -> MarkPhotoReason.NO_PHOTO_SUPPORT
        callActive() -> MarkPhotoReason.CAMERA_BUSY
        else -> when (worn()) { false -> MarkPhotoReason.GLASSES_NOT_WORN; true -> null; null -> if (captureRunning()) null else MarkPhotoReason.GLASSES_NOT_WORN }
    }

    override suspend fun request(markRef: String): Boolean = send(markRef)
}
