package com.rokid.context.work.frames.place

import com.rokid.context.work.frames.scene.ImageSpaceTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSpaceIdTest {
    private fun t(id: String, fp: String) = ImageSpaceTarget(id, fp, 4, { emptyList() }, { FloatArray(4) })

    @Test fun fingerprintMapsToTheSpaceRowIdThatKeysFrameVectors() {
        val ts = listOf(t("uuid-a", "siglip2:x"), t("uuid-b", "nomic:y"))
        assertEquals("uuid-b", PlaceSamplers.frameSpaceId(ts, "nomic:y"))
    }

    @Test fun unknownFingerprintStaysAsIsSoItFindsNothing() {
        assertEquals("zzz", PlaceSamplers.frameSpaceId(listOf(t("uuid-a", "siglip2:x")), "zzz"))
    }
}
