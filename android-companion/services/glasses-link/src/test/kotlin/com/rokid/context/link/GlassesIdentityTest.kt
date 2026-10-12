package com.rokid.context.link

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesIdentityTest {
    @Test fun sessionTargetsOurGlassesApp() {
        assertEquals("com.rokid.context.glasses", CxrGlassesLink.GLASSES_PKG)
        assertEquals("com.rokid.context.glasses.MainActivity", CxrGlassesLink.GLASSES_ACTIVITY)
    }

    /** The CUSTOM_APP session opens the glasses app by id and class: a rename there must fail here. */
    @Test fun glassesBuildUsesTheSameIdAndActivity() {
        val gradle = File("../../glasses/build.gradle.kts").readText()
        assertTrue("applicationId = \"${CxrGlassesLink.GLASSES_PKG}\"" in gradle)
        assertTrue(File("../../glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt").isFile)
    }
}
