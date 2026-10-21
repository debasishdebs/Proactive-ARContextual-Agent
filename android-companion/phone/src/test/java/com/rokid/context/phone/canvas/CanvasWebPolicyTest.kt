package com.rokid.context.phone.canvas.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasWebPolicyTest {
    @Test fun allowsOwnOrigin() {
        assertTrue(CanvasWebPolicy.allow("http://127.0.0.1:8765/", 8765))
        assertTrue(CanvasWebPolicy.allow("http://127.0.0.1:8765/app.js", 8765))
        assertTrue(CanvasWebPolicy.allow("http://127.0.0.1:8766/#/pipelines", 8766))
    }

    @Test fun rejectsOtherPortHostOrScheme() {
        assertFalse(CanvasWebPolicy.allow("http://127.0.0.1:8766/", 8765))
        assertFalse(CanvasWebPolicy.allow("http://127.0.0.1/", 8765))
        assertFalse(CanvasWebPolicy.allow("http://localhost:8765/", 8765))
        assertFalse(CanvasWebPolicy.allow("http://192.168.1.20:8765/", 8765))
        assertFalse(CanvasWebPolicy.allow("https://127.0.0.1:8765/", 8765))
        assertFalse(CanvasWebPolicy.allow("file:///data/x", 8765))
        assertFalse(CanvasWebPolicy.allow("javascript:alert(1)", 8765))
        assertFalse(CanvasWebPolicy.allow("http://127.0.0.1:8765@evil.example/", 8765))
        assertFalse(CanvasWebPolicy.allow("http://evil.example/http://127.0.0.1:8765/", 8765))
        assertFalse(CanvasWebPolicy.allow("http://127.0.0.1:87650/", 8765))
        assertFalse(CanvasWebPolicy.allow("", 8765))
    }
}
