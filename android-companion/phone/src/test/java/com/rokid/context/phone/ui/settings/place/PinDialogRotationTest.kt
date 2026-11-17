package com.rokid.context.phone.ui.settings.place

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.PinRefusal
import com.rokid.context.work.frames.place.PinResult
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlacePinning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The pin dialog survives a rotation (configuration change) with its typed text and the in-memory fix; leaving the screen drops both. No coordinate is in any saved state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PinDialogRotationTest {
    companion object { @Volatile lateinit var holder: Ctl }
    @get:Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    class Ctl : PlaceGuideControl {
        override var enabled = false
        var stored = listOf<UserPlace>()
        override suspend fun seed(box: GeoBox): Result<String> = Result.success("")
        override suspend fun summary() = ""
        override val pinning = PlacePinning(
            fix = { PlaceFix(7.957, 80.759, 1_000, 10f) }, zoneBlocked = { false }, zones = { emptyList() },
            nearby = { _, _ -> emptyList<PlaceEntity>() }, load = { stored }, save = { stored = it; true }, elapsed = { 5_000L },
            schedule = { _, _ -> {} },
        )
    }

    /** A rotation is simulated without a Robolectric-built activity (a buildActivity window leaks into the JVM and later Compose tests never get idle): the ViewModelStore is retained, the route is disposed with the configuration-change flag set, then composed again. */
    private class Owner : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    private fun host(owner: Owner, shown: androidx.compose.runtime.MutableState<Boolean>, changing: () -> Boolean) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) {
            if (shown.value) PlaceGuideRoute(holder, back = {}, changingConfigurations = changing)
        }
    }

    private fun openDialog() {
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("pin_here"))
        compose.onNodeWithTag("pin_here").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("pin_dialog")).fetchSemanticsNodes().size > 0 }
    }

    @Test fun rotationKeepsTheDialogTypedTextAndFixThenLeavingDropsThem() {
        val c = Ctl(); holder = c
        val owner = Owner(); val shown = mutableStateOf(true); var changing = false
        host(owner, shown) { changing }
        openDialog()
        compose.onNodeWithTag("pin_f_name").performTextInput("Mirror Wall")
        assertTrue(c.pinning.hasDraft)

        changing = true; shown.value = false; compose.waitForIdle() // a configuration change: the route is disposed, the ViewModel store is retained
        changing = false; shown.value = true; compose.waitForIdle()
        compose.onNodeWithTag("pin_dialog").assertIsDisplayed()
        compose.onNodeWithTag("pin_f_name").assertIsDisplayed()
        assertTrue("the held position survives a rotation", c.pinning.hasDraft)
        compose.onNodeWithTag("pin_f_save").performClick()
        compose.waitUntil(5_000) { c.stored.isNotEmpty() }
        assertEquals("Mirror Wall", c.stored.single().name)
        owner.viewModelStore.clear()
    }

    @Test fun leavingTheScreenDropsTheDraft() {
        val c = Ctl(); holder = c
        val owner = Owner(); val shown = mutableStateOf(true)
        host(owner, shown) { false }
        openDialog()
        assertTrue(c.pinning.hasDraft)
        shown.value = false; compose.waitForIdle() // really leaving, not recreating
        assertFalse(c.pinning.hasDraft)
        owner.viewModelStore.clear()
    }

    @Test fun anExpiredDraftAfterRotationIsRefusedOnSaveSoTheUserStartsAgain() = runBlocking {
        val c = Ctl()
        c.pinning.begin(); c.pinning.expire()
        assertEquals(PinResult.Refused(PinRefusal.DRAFT_EXPIRED), c.pinning.create(com.rokid.context.work.frames.place.PinForm("x", PlacePinning.KINDS.first(), 60, null, "")))
    }

    @Test fun clearingTheViewModelStoreCancelsTheDraftButADisposeDuringRotationDoesNot() {
        assertFalse(dropsDraftOnDispose(changingConfigurations = true))
        assertTrue(dropsDraftOnDispose(changingConfigurations = false))
        val c = Ctl(); val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = PlaceGuideViewModel(c, Dispatchers.Unconfined) as T
        })[PlaceGuideViewModel::class.java]
        vm.beginPin(); compose.waitUntil(5_000) { c.pinning.hasDraft }
        store.clear()
        assertFalse(c.pinning.hasDraft)
    }

    @androidx.compose.runtime.Composable private fun rememberChanging(): () -> Boolean { val a = androidx.compose.ui.platform.LocalContext.current as android.app.Activity; return { a.isChangingConfigurations } }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
