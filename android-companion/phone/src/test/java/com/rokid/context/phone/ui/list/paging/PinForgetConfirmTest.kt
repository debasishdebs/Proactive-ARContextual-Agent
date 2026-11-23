package com.rokid.context.phone.ui.list.paging

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.FateCounts
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.orchestration.forget.ForgetActions.Outcome
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.memory.ForgetCopy
import com.rokid.context.phone.ui.memory.ForgetScopeCopy
import com.rokid.context.phone.ui.preview.FakeActivityData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Slice 1.4.2: the Forget confirmation of a Moment names the pinned places that go with it (UI only), through the EXISTING multi-select state machine (the pins ride on the planner's preview):
 * cancel changes nothing, confirm forgets with the plan's own token, and a selection with more than five pins lists five and says how many more.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PinForgetConfirmTest {
    private class MapStore : StateStore { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun set(key: String, value: String) { m[key] = value } }
    private fun preview(pins: Int = 0, names: List<String> = emptyList(), hash: String = "h1") =
        SelectionPreview(2, emptySet(), 2, mapOf(Family.MEMORY to FateCounts(2, 0, 0)), hash, false, false, pins = pins, pinNames = names)

    private class FakePort(val p: SelectionPreview) : SelectionForgetPort {
        val forgot = ArrayList<Pair<Set<Ref>, String>>()
        val pinsSeen = ArrayList<Set<String>>()
        override suspend fun preview(roots: Set<Ref>): SelectionPreview? = p
        override suspend fun forget(roots: Set<Ref>, token: String): Outcome { forgot += roots to token; return Outcome.DONE }
        override suspend fun forget(roots: Set<Ref>, token: String, pins: Set<String>): Outcome { pinsSeen += pins; return forget(roots, token) }
    }
    private class Rig(port: FakePort) {
        var refreshes = 0
        val ctl = SelectionController(ListKind.MOMENTS, SelectionResolver(ListKind.MOMENTS, FakeActivityData(), { java.time.ZoneOffset.UTC }), { port }, MapStore(), CoroutineScope(Dispatchers.Unconfined)) { refreshes++ }
        val stage get() = ctl.state.value.stage
    }

    @Test fun theConfirmationCarriesThePinsAndTheirNamesAndTheButtonSaysSo() {
        val port = FakePort(preview(2, listOf("Mirror Wall", "Lion's Paw")))
        val r = Rig(port); r.ctl.enter("m1"); r.ctl.toggle("m2"); r.ctl.forgetSelected()
        val p = (r.stage as ForgetStage.Confirm).preview
        assertEquals(2, p.pins); assertEquals(listOf("Mirror Wall", "Lion's Paw"), p.pinNames)
        val line = ForgetScopeCopy.pinsLine(p.pins, p.pinNames)
        assertTrue(line.contains("“Mirror Wall”") && line.contains("“Lion's Paw”")); assertTrue("irreversible", line.contains("cannot be undone")); assertTrue(line.contains("place cards may stop appearing"))
        assertEquals("Forget Moment and pin(s)", ForgetCopy.CONFIRM_WITH_PINS)
    }

    @Test fun cancelChangesNothingAndConfirmForgetsWithThePlansToken() {
        val port = FakePort(preview(1, listOf("Mirror Wall"), hash = "tok"))
        val r = Rig(port); r.ctl.enter("m1"); r.ctl.forgetSelected()
        r.ctl.dismiss()
        assertTrue("cancel forgets nothing", port.forgot.isEmpty()); assertEquals(ForgetStage.Idle, r.stage); assertEquals(1, r.ctl.state.value.selection.size)
        r.ctl.forgetSelected(); r.ctl.confirm()
        assertEquals("tok", port.forgot.single().second); assertEquals(1, r.refreshes)
    }

    @Test fun moreThanFivePinsListFiveAndSayHowManyMore() {
        val names = (1..5).map { "Pin $it" }
        val line = ForgetScopeCopy.pinsLine(8, names)
        names.forEach { assertTrue(line.contains("“$it”")) }
        assertTrue(line.contains("and 3 more")); assertTrue(line.contains("Also deletes 8 pinned places"))
        assertFalse("six names are never listed", line.contains("Pin 6"))
        assertEquals("", ForgetScopeCopy.pinsLine(0, emptyList()))
        assertTrue(ForgetScopeCopy.pinsLine(1, listOf("A")).startsWith("Also deletes 1 pinned place that uses this Moment"))
    }

    @Test fun aSelectionWithoutPinsShowsNoPinLine() {
        val r = Rig(FakePort(preview())); r.ctl.enter("m1"); r.ctl.forgetSelected()
        assertEquals(0, (r.stage as ForgetStage.Confirm).preview.pins)
    }

    @Test fun withPinsKeepsThePlanAndTheTokenUntouched() {
        val base = preview(hash = "same").withPins(3, listOf("A", "B", "C"))
        assertEquals("same", base.planHash); assertEquals("same", base.token(false)); assertEquals(3, base.pins); assertEquals(2, base.nodes)
    }

    // Codex pin r2 #1: the multi-select Forget is bound to the pins the dialog named, and to none when the pin lookup failed.
    @Test fun theConfirmedForgetCarriesOnlyThePinIdsTheDialogNamed() {
        val port = FakePort(preview(1, listOf("A")).withPins(1, listOf("A"), setOf("pin-1"), failed = false))
        val r = Rig(port); r.ctl.enter("m1"); r.ctl.forgetSelected(); r.ctl.confirm()
        assertEquals(listOf(setOf("pin-1")), port.pinsSeen)
    }

    @Test fun aFailedPinLookupConfirmsWithNoPinAtAll() {
        val failed = preview().withPins(0, emptyList(), setOf("pin-9"), failed = true)
        assertEquals(emptySet<String>(), failed.allowedPins)
        val port = FakePort(failed)
        val r = Rig(port); r.ctl.enter("m1"); r.ctl.forgetSelected(); r.ctl.confirm()
        assertEquals(listOf(emptySet<String>()), port.pinsSeen)
        assertEquals(emptySet<String>(), preview().allowedPins)
    }
}
