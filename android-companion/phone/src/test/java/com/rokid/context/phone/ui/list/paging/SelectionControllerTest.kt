package com.rokid.context.phone.ui.list.paging

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.FateCounts
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.orchestration.forget.ForgetActions.Outcome
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.preview.FakeActivityData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A7/A8: the multi-select state machine over a fake planner: confirm, acknowledgement above 200, a changed set re-opens the confirmation, refusal above 5,000, one refresh after the receipt, nothing with the flag OFF. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SelectionControllerTest {
    private class MapStore : StateStore { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun set(key: String, value: String) { m[key] = value } }

    private fun preview(roots: Int, nodes: Int = roots, needsAck: Boolean = false, tooLarge: Boolean = false, missing: Set<Ref> = emptySet(), hash: String = "h1") =
        SelectionPreview(roots, missing, nodes, mapOf(Family.MEMORY to FateCounts(roots, 0, 0), Family.ANSWER to FateCounts(0, 2, 0)), hash, needsAck, tooLarge)

    private class FakePort(var previews: MutableList<SelectionPreview?>, var outcomes: MutableList<Outcome>) : SelectionForgetPort {
        val forgot = ArrayList<Pair<Set<Ref>, String>>(); var previewCalls = 0
        override suspend fun preview(roots: Set<Ref>): SelectionPreview? { previewCalls++; return if (previews.size > 1) previews.removeAt(0) else previews.firstOrNull() }
        override suspend fun forget(roots: Set<Ref>, token: String): Outcome { forgot += roots to token; return outcomes.removeAt(0) }
    }

    private class Rig(port: FakePort?, kind: ListKind = ListKind.MOMENTS, val store: MapStore = MapStore()) {
        var refreshes = 0
        val ctl = SelectionController(kind, SelectionResolver(kind, FakeActivityData(), { java.time.ZoneOffset.UTC }), { port }, store, CoroutineScope(Dispatchers.Unconfined)) { refreshes++ }
        val stage get() = ctl.state.value.stage
        val sel get() = ctl.state.value.selection
    }

    @Test fun withNoPortTheFeatureIsOffAndNothingChanges() {
        val r = Rig(null); r.ctl.enter("m1"); r.ctl.toggle("m2"); r.ctl.selectPage(listOf("m3")); r.ctl.forgetSelected()
        assertFalse(r.ctl.available); assertEquals(Selection(), r.sel); assertEquals(ForgetStage.Idle, r.stage)
    }

    @Test fun answersAndNudgesAreNotSelectableEvenWithTheFlagOn() {
        val r = Rig(FakePort(mutableListOf(preview(1)), mutableListOf()), ListKind.ANSWERS); r.ctl.enter("a1")
        assertFalse(r.ctl.available); assertEquals(Selection(), r.sel)
    }

    @Test fun aSmallSelectionPlansConfirmsAndForgetsWithThePlanHashThenRefreshesOnce() {
        val port = FakePort(mutableListOf(preview(2)), mutableListOf(Outcome.DONE)); val r = Rig(port)
        r.ctl.enter("m1"); r.ctl.toggle("m2"); r.ctl.forgetSelected()
        val c = r.stage as ForgetStage.Confirm; assertTrue(c.canConfirm); assertFalse(c.changed)
        r.ctl.confirm()
        assertEquals(1, port.forgot.size); assertEquals(setOf(Ref(Family.MEMORY, "m1"), Ref(Family.MEMORY, "m2")), port.forgot[0].first); assertEquals("h1", port.forgot[0].second)
        assertEquals(Selection(), r.sel); assertEquals(ForgetStage.Idle, r.stage); assertEquals(SelectionText.forgot(2), r.ctl.state.value.message); assertEquals(1, r.refreshes)
    }

    @Test fun aLargeSelectionNeedsTheTickAndSendsTheAckToken() {
        val big = preview(SelectionLimits.ACK_ABOVE + 1, needsAck = true)
        val port = FakePort(mutableListOf(big), mutableListOf(Outcome.DONE)); val r = Rig(port)
        r.ctl.selectPage((1..201).map { "m$it" }); r.ctl.forgetSelected()
        val c = r.stage as ForgetStage.Confirm; assertTrue(c.preview.needsAck); assertFalse(c.canConfirm)
        r.ctl.confirm(); assertTrue("not acknowledged: nothing runs", port.forgot.isEmpty())
        r.ctl.acknowledge(true); assertTrue((r.stage as ForgetStage.Confirm).canConfirm)
        r.ctl.confirm(); assertEquals(big.planHash + "+ack", port.forgot.single().second)
    }

    @Test fun aChangedSetReopensTheConfirmationWithFreshCountsAndAnEmptyAck() {
        val port = FakePort(mutableListOf(preview(2, hash = "old"), preview(3, nodes = 3, hash = "new")), mutableListOf(Outcome.CONTENT_CHANGED, Outcome.DONE)); val r = Rig(port)
        r.ctl.enter("m1"); r.ctl.enter("m2"); r.ctl.forgetSelected(); r.ctl.confirm()
        val c = r.stage as ForgetStage.Confirm; assertTrue(c.changed); assertFalse(c.acknowledged); assertEquals(3, c.preview.nodes)
        assertEquals("nothing was forgotten and the selection is kept", 2, r.sel.size); assertEquals(0, r.refreshes)
        r.ctl.confirm(); assertEquals("new", port.forgot.last().second); assertEquals(1, r.refreshes)
    }

    @Test fun aSelectionAboveTheCeilingIsRefusedBeforeAnythingIsPlanned() {
        val port = FakePort(mutableListOf(preview(1)), mutableListOf()); val r = Rig(port)
        r.ctl.selectPage((1..SelectionLimits.REFUSE_ABOVE + 1).map { "m$it" }); r.ctl.forgetSelected()
        assertEquals(ForgetStage.Refused(ForgetStage.Reason.CEILING, SelectionLimits.REFUSE_ABOVE + 1), r.stage); assertEquals(0, port.previewCalls)
        assertTrue(SelectionText.refusedCeiling(5001).contains("Select fewer"))
        r.ctl.dismiss(); assertEquals(ForgetStage.Idle, r.stage); assertEquals(5001, r.sel.size)
    }

    @Test fun aPlanTooLargeForOneStepIsRefusedAndNothingIsSent() {
        val port = FakePort(mutableListOf(preview(5, tooLarge = true)), mutableListOf()); val r = Rig(port)
        r.ctl.enter("m1"); r.ctl.forgetSelected()
        assertEquals(ForgetStage.Refused(ForgetStage.Reason.TOO_LARGE, 1), r.stage); assertTrue(port.forgot.isEmpty())
        assertTrue(SelectionText.REFUSED_TOO_LARGE.contains("Select fewer"))
    }

    @Test fun refsThePlannerCouldNotFindLeaveTheSelectionAndAnAllGoneSelectionSaysSo() {
        val gone = Ref(Family.MEMORY, "m2")
        val port = FakePort(mutableListOf(preview(2, nodes = 1, missing = setOf(gone))), mutableListOf()); val r = Rig(port)
        r.ctl.enter("m1"); r.ctl.enter("m2"); r.ctl.forgetSelected()
        assertEquals(setOf(Ref(Family.MEMORY, "m1")), r.sel.refs); assertTrue(r.stage is ForgetStage.Confirm)
        val none = FakePort(mutableListOf(preview(1, nodes = 0, missing = setOf(Ref(Family.MEMORY, "x")))), mutableListOf()); val r2 = Rig(none)
        r2.ctl.enter("x"); r2.ctl.forgetSelected()
        assertEquals(Selection(), r2.sel); assertEquals(SelectionText.NOTHING_LEFT, r2.ctl.state.value.message); assertEquals(ForgetStage.Idle, r2.stage)
    }

    @Test fun failuresKeepTheSelectionAndSayWhy() {
        val port = FakePort(mutableListOf(preview(1)), mutableListOf(Outcome.SAVE_IN_PROGRESS, Outcome.FAILED)); val r = Rig(port)
        r.ctl.enter("m1"); r.ctl.forgetSelected(); r.ctl.confirm()
        assertEquals(SelectionText.BUSY, r.ctl.state.value.message); assertEquals(1, r.sel.size); assertEquals(ForgetStage.Idle, r.stage)
        r.ctl.forgetSelected(); r.ctl.confirm(); assertEquals(SelectionText.FAILED, r.ctl.state.value.message); assertEquals(1, r.sel.size); assertEquals(0, r.refreshes)
        val none = Rig(FakePort(mutableListOf(null), mutableListOf())); none.ctl.enter("m1"); none.ctl.forgetSelected()
        assertEquals(SelectionText.PREVIEW_FAILED, none.ctl.state.value.message); assertEquals(ForgetStage.Idle, none.stage)
    }

    @Test fun theSelectionIsSavedAndComesBackAfterProcessDeath() {
        val store = MapStore(); val a = Rig(FakePort(mutableListOf(preview(1)), mutableListOf()), store = store)
        a.ctl.enter("m1"); a.ctl.selectPage(listOf("m2", "m3"))
        val b = Rig(FakePort(mutableListOf(preview(1)), mutableListOf()), store = store)
        assertEquals(a.sel, b.sel); assertEquals(3, b.sel.size); assertTrue(b.sel.active)
        b.ctl.clear(); assertNull(MapStore().get("x")); assertEquals(Selection(), Rig(null, store = store).sel)
    }
}
