package com.rokid.context.glasses.capture.session

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YuvCopyTest {
    @Test fun thumbIsAbout160WideAndSubsamplesTheYPlane() {
        val w = 640; val h = 480
        val y = ByteArray(w * h) { i -> ((i % w) / 4).toByte() } // a horizontal ramp: 0..159 over the width
        val t = YuvCopy.thumb(ByteBuffer.wrap(y), w, 1, w, h)
        assertEquals(160, t.w); assertEquals(120, t.h)
        val row = (0 until 160).map { t.luma[60 * 160 + it].toInt() and 0xFF }
        assertTrue(row.zipWithNext().all { (a, b) -> b >= a }, "the ramp survives")
        assertEquals(0, row.first()); assertTrue(row.last() >= 158)
    }

    @Test fun thumbReadsAPaddedPlane() {
        val w = 640; val h = 480; val stride = 704
        val y = ByteArray(stride * h) { 255.toByte() } // padding is bright; picture is dark
        for (r in 0 until h) for (c in 0 until w) y[r * stride + c] = 10
        val t = YuvCopy.thumb(ByteBuffer.wrap(y), stride, 1, w, h)
        assertTrue(t.luma.all { (it.toInt() and 0xFF) == 10 }, "padding never leaks into the thumbnail")
    }

    @Test fun thumbOfAPortraitBufferIsStill160Wide() {
        val t = YuvCopy.thumb(ByteBuffer.wrap(ByteArray(480 * 640)), 480, 1, 480, 640)
        assertEquals(160, t.w); assertEquals(213, t.h)
    }

    @Test fun nv21InterleavesVThenUAndHonoursStrides() {
        val w = 8; val h = 4
        val y = ByteArray(10 * h) { 7 }; for (r in 0 until h) for (c in 0 until w) y[r * 10 + c] = (r * w + c).toByte() // row stride 10
        // chroma planes of the camera: pixel stride 2, interleaved in one buffer each; u is [1] of the pair, v is [0] in NV21 terms
        val u = ByteArray(w) { 100 }; val v = ByteArray(w) { 200.toByte() }
        val f = YuvCopy.nv21(ByteBuffer.wrap(y), 10, 1, ByteBuffer.wrap(u), 8, 2, ByteBuffer.wrap(v), 8, 2, w, h, 90)
        assertEquals(w * h * 3 / 2, f.data.size)
        assertEquals(90, f.rotation)
        for (i in 0 until w * h) assertEquals(i.toByte(), f.data[i])
        assertEquals(200.toByte(), f.data[w * h]) // V first
        assertEquals(100.toByte(), f.data[w * h + 1])
    }

    @Test fun rotatedWidthAndScaling() {
        assertEquals(640, YuvCopy.rotatedWidth(640, 480, 0)); assertEquals(640, YuvCopy.rotatedWidth(480, 640, 90))
        assertEquals(640, YuvCopy.rotatedWidth(480, 640, 270)); assertEquals(480, YuvCopy.rotatedWidth(480, 640, 0))
    }
}
