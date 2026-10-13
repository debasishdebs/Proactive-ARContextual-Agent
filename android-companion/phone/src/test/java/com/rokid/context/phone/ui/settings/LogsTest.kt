package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LogsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun keyLookingLinesAreDroppedAndRefreshReloads() = runBlocking {
        assertEquals(listOf("I/RoleRunner: ANSWER v1 ok"), LogReader.redact(listOf("I/RoleRunner: ANSWER v1 ok", "D/OkHttp: Authorization: Bearer sk-1", "x api-key: abc")))
        var calls = 0
        val vm = LogsViewModel { calls++; listOf("line $calls") }
        assertEquals("line 1", withTimeout(5_000) { vm.state.first { it.text.isNotEmpty() } }.text)
        vm.refresh()
        assertTrue(withTimeout(5_000) { vm.state.first { it.text == "line 2" } }.text.isNotEmpty())
        vm.cancelScope()
    }
}
