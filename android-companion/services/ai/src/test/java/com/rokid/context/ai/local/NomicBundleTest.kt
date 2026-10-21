package com.rokid.context.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class NomicBundleTest {
    @Test fun fingerprintCombinesTextAndImageDims() {
        assertEquals("nomic-embed-text-v1.5|mean|ln|d=256|l2+nomic-embed-vision-v1.5|cls|l2|d=768|l2", NomicBundle.fingerprint())
        assertEquals(NomicBundle.fingerprint(NomicVision.STORED_DIMS), NomicBundle.fingerprint())
        assertEquals(NomicText.fingerprint(256) + "+" + NomicVision.fingerprint(768), NomicBundle.fingerprint())
    }

    @Test fun differentImageDimsDifferentSpace() {
        assertNotEquals(NomicBundle.fingerprint(768), NomicBundle.fingerprint(256))
        assertEquals("nomic-embed-text-v1.5|mean|ln|d=256|l2+nomic-embed-vision-v1.5|cls|l2|d=256|l2", NomicBundle.fingerprint(256))
    }

    @Test fun visionPathIsSibling() {
        assertEquals(File("/data/models/nomic-embed-vision-v1.5.model_quantized.onnx"), NomicBundle.visionPath("/data/models/nomic-embed-text-v1.5.Q8_0.gguf"))
        assertEquals("nomic-embed-vision-v1.5.model_quantized.onnx", NomicBundle.VISION_FILE)
    }
}
