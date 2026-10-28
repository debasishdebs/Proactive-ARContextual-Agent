package com.rokid.context.phone.hub

import com.rokid.context.core.model.MarkPhotoReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.9.7 slice D: when a phone-origin mark may ask the glasses for a photo, and the reason when it may not. */
class HubMarkPhotoTest {
    private var connected = true; private var supports = true; private var worn: Boolean? = true; private var running = true; private var call = false
    private val g = HubMarkPhoto({ connected }, { supports }, { worn }, { running }, { call }, { true })

    @Test fun connectedWornIdleAndSupportedMeansAsk() { assertNull(g.reasonNow()) }
    @Test fun linkDownIsGlassesNotConnected() { connected = false; assertEquals(MarkPhotoReason.GLASSES_NOT_CONNECTED, g.reasonNow()) }
    @Test fun anOldGlassesIsNoPhotoSupport() { supports = false; assertEquals(MarkPhotoReason.NO_PHOTO_SUPPORT, g.reasonNow()) }
    @Test fun aCallIsCameraBusy() { call = true; assertEquals(MarkPhotoReason.CAMERA_BUSY, g.reasonNow()) }
    @Test fun notWornIsGlassesNotWorn() { worn = false; assertEquals(MarkPhotoReason.GLASSES_NOT_WORN, g.reasonNow()) }
    @Test fun unknownWearCountsAsWornOnlyWhileCaptureRuns() {
        worn = null; assertNull(g.reasonNow())
        running = false; assertEquals(MarkPhotoReason.GLASSES_NOT_WORN, g.reasonNow())
    }
    @Test fun notConnectedWinsOverEverythingElse() { connected = false; supports = false; call = true; worn = false; assertEquals(MarkPhotoReason.GLASSES_NOT_CONNECTED, g.reasonNow()) }
    @Test fun theRequestGoesThroughTheSendSeam() = runBlocking {
        val sent = ArrayList<String>()
        val h = HubMarkPhoto({ true }, { true }, { true }, { true }, { false }, { sent += it; true })
        assertEquals(true, h.request("phone:1")); assertEquals(listOf("phone:1"), sent)
    }
}
