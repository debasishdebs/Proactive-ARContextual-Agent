package com.rokid.context.phone.ui.settings.capture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Forget scope setting reaches the Forget path only if the app binds the provider at start (like the photo Forget radius): a source pin, because the Application class is not unit-testable. */
class ForgetScopeWiringTest {
    @Test fun theApplicationBindsTheScopeProviderToTheStoredSetting() {
        val src = File("src/main/java/com/rokid/context/ContextApp.kt").also { assertTrue("run from the phone module: ${it.absolutePath}", it.exists()) }.readText()
        assertTrue(src.lines().any { "ForgetScopeSetting.provider" in it && "timing.forgetScopeNow()" in it && !it.trimStart().startsWith("//") })
        assertTrue("the radius binding it sits next to", src.contains("ForgetRadius.provider = { timing.forgetRadiusMs() }"))
    }
}
