package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.AuditStep
import com.rokid.context.memory.moment.DeriveRegistry
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The derive step: real SUMMARY and CAPTION ports for Moment and Scene, the bounded worker pass and its stored cadence, all against a fake model. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DerivePassTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val builder = SceneBuilder(db, enabled = { true }, clock = { 5 })
    private val audit = ObjectAudit(db.objectDao()) { 7 }
    private val exec = SceneInputExecutor(db)
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    private val epoch = DeletionEpoch("i", 1, 0)
    private val cadence = DeriveCadence(db)
    private val inputs = DeriveInputs(db)
    private var admitCalls = 0
    private var refuseAdmitOnCall = 0
    private val prompts = ArrayList<String>()
    private var onModel: suspend () -> Unit = {}
    private var fail = false
    private var admitted = true
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)

    private val model = DeriveText { p, _, _ -> prompts += p; onModel(); if (fail) throw IllegalStateException("model down"); "REPLY-${prompts.size}" }
    private fun pass(max: Int = 10, text: DeriveText = model): DerivePass {
        val gate = DeriveGate(enabled = { cadence.enabled() }, admit = { admitCalls++; admitted && (refuseAdmitOnCall == 0 || admitCalls != refuseAdmitOnCall) })
        val reg = DeriveRegistry().also { r -> TextDerivePorts(inputs, text, gate).registerIn(r) }
        return DerivePass(db, reg, cadence, { DaoFence(0, epoch, { 0 }, { epoch }) }, inputs, gate, max)
    }

    private suspend fun photo(sec: Long, memoryText: String? = "cat on sofa $sec", motion: String = "STILL") {
        fx.frame("f$sec", t(sec) + 5)
        db.frameMetaDao().upsert(FrameMetaEntity("f$sec", null, null, motion, false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = t(sec) + 5))
        if (memoryText != null) db.memoryDao().insert(MemoryEntity("m$sec", "f$sec", memoryText, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, memoryText))
        moments.build(listOf(mid(sec)))
    }
    private suspend fun scenes() = builder.rebuildWindow(t(0), t(3599))
    private suspend fun on() { cadence.setEnabled(true) }
    private suspend fun sceneText() = exec.run(t(0), t(3599)).first().text

    // codex r13 P1-2: the text, its provenance and the wipe generation are captured in ONE snapshot; a Forget or Delete all after the last check cannot
    // empty the provenance the call carries, and the router's re-check (ForgetFence / generation) then refuses the send
    @Test fun aForgetAfterTheLastCheckCannotEmptyTheProvenanceOfLoadedText() = runBlocking<Unit> {
        photo(0, "cat on sofa SECRET"); on()
        val calls = ArrayList<Triple<String, com.rokid.context.model.PayloadSources, Long>>()
        val capture = DeriveText { p, src, gen -> calls += Triple(p, src, gen); "REPLY" }
        inputs.testBeforeSend = { inputs.testBeforeSend = null; forget.forgetMoments(listOf(mid(0))) } // the cascade removes the Moment's parts
        pass(text = capture).runIfDue(MS)
        val (prompt, src, gen) = calls.first { "SECRET" in it.first }
        assertTrue(prompt.contains("SECRET"))
        assertTrue("the loaded text keeps its provenance", src.stored && "m0" in src.memoryIds)
        assertTrue("the router's re-check sees the memory gone", com.rokid.context.memory.forget.ForgetFence.goneAmong(db, src.memoryIds).contains("m0"))
        assertEquals(com.rokid.context.orchestration.MemoryWipe.generation.get(), gen)
    }

    // codex r14 P1-3 / RULE C: a mark-only Moment ("sleeping") has no frame or memory ids, yet its label is stored content: the call it makes is guarded
    // (not NONE), so the router takes the slot and checks the captured generation; a Delete all held at admission drops it
    @Test fun aMarkOnlyMomentsLabelIsGuardedByTheCapturedGeneration() = runBlocking<Unit> {
        fx.mark("mk:1:1", t(0) + 100, "voice"); fx.mark("mk:1:1:i", t(0) + 100, "voice", "INTENT", "sleeping"); moments.build(listOf(mid(0))); on()
        var captured: Pair<com.rokid.context.model.PayloadSources, Long>? = null
        pass(text = DeriveText { p, src, gen -> if ("sleeping" in p && captured == null) captured = src to gen; "REPLY" }).runIfDue(MS)
        val (src, gen) = checkNotNull(captured) { "the mark-only Moment made a call" }
        assertTrue("its label is stored content, never NONE", src.stored && src != com.rokid.context.model.PayloadSources.NONE)
        com.rokid.context.orchestration.MemoryWipe.generation.incrementAndGet() // Delete all lands while the call waits at admission
        assertTrue(com.rokid.context.work.frames.FrameLiveness(gen, { it }, { emptySet() }).gone(src.frameIds).wiped)
    }

    @Test fun aDeleteAllAfterTheSnapshotIsSeenThroughTheCapturedGeneration() = runBlocking<Unit> {
        photo(0); on()
        var captured: Pair<com.rokid.context.model.PayloadSources, Long>? = null
        inputs.testBeforeSend = { inputs.testBeforeSend = null; com.rokid.context.orchestration.MemoryWipe.generation.incrementAndGet() } // Delete all commits
        pass(text = DeriveText { _, src, gen -> if (captured == null) captured = src to gen; "REPLY" }).runIfDue(MS)
        val (src, gen) = checkNotNull(captured)
        val l = com.rokid.context.work.frames.FrameLiveness(gen, { it }, { emptySet() })
        assertTrue("wiped since the snapshot: the router drops the call", l.gone(src.frameIds).wiped)
    }

    @Test fun sceneTextBecomesNonEmptyAfterADeriveWithAFakeModel() = runBlocking<Unit> {
        photo(0); photo(30); scenes(); on()
        assertEquals("", sceneText())
        val r = pass().runIfDue(MS)
        assertNotNull(r); assertTrue(r!!.written >= 2)
        assertTrue(sceneText().contains("REPLY-"))
        assertEquals(setOf(DerivedField.SUMMARY, DerivedField.CAPTION), exec.run(t(0), t(3599)).first().derived.keys) // built from the Scene's CURRENT input revision, so readers see them
        assertNotNull(audit.readDerived(ObjectRef(ObjectKind.SCENE, sid(0)), DerivedField.SUMMARY)); assertNotNull(audit.readDerived(ObjectRef(ObjectKind.SCENE, sid(0)), DerivedField.CAPTION))
        assertNotNull(audit.readDerived(ObjectRef(ObjectKind.MOMENT, mid(0)), DerivedField.SUMMARY))
        val m0 = ObjectRef(ObjectKind.MOMENT, mid(0), 1); assertEquals(audit.inputSeq(m0), audit.readDerived(m0, DerivedField.SUMMARY)?.builtRev) // a Moment records the input revision it was built from
        assertNull("no embedding model yet", audit.readDerived(ObjectRef(ObjectKind.SCENE, sid(0)), DerivedField.EMBEDDING))
    }

    @Test fun theCadenceIsOffByDefaultFourADayAndSeparateFromTheSceneBuildCadence() = runBlocking<Unit> {
        photo(0); scenes()
        assertFalse(cadence.enabled()); assertEquals(4, cadence.perDay())
        assertNull(pass().runIfDue(MS)); assertTrue(prompts.isEmpty())
        on(); cadence.setPerDay(1000); assertEquals(96, cadence.perDay()); cadence.setPerDay(0); assertEquals(1, cadence.perDay()); cadence.setPerDay(4)
        assertEquals(24, SceneCadence(db, builder).perDay()) // the Scene build cadence is untouched (its own default: hourly)
        assertNotNull(pass().runIfDue(MS)); assertNull(pass().runIfDue(MS + 60 * 60_000L)) // 4 a day = every 6 hours
        assertNotNull(pass().runIfDue(MS + 6 * 60 * 60_000L))
    }

    @Test fun notAdmittedSkipsAndStaysDue() = runBlocking<Unit> {
        photo(0); scenes(); on(); admitted = false
        assertNull(pass().runIfDue(MS)); assertTrue(prompts.isEmpty())
        admitted = true; assertNotNull(pass().runIfDue(MS + 1000))
    }

    @Test fun hiddenAndStaleFieldsAreNeverFedToThePrompt() = runBlocking<Unit> {
        photo(0, "visible note"); photo(30, "INVALIDATED-SECRET"); db.memoryViewDao().moveReviewState("m30", "OK", "INVALIDATED"); scenes(); on() // the Moment still holds the memory part, but readers no longer see it
        // a Moment summary that was hidden (stale) must not reach the Scene prompt
        val m0 = ObjectRef(ObjectKind.MOMENT, mid(0), 1)
        audit.writeDerived(m0, DerivedField.SUMMARY, "STALE-SUMMARY", null, audit.inputSeq(m0)); audit.markStale(ObjectKind.MOMENT, mid(0))
        pass().runIfDue(MS)
        assertTrue(prompts.isNotEmpty()); assertTrue(prompts.any { it.contains("visible note") })
        prompts.forEach { assertFalse(it, it.contains("INVALIDATED-SECRET")); assertFalse(it, it.contains("STALE-SUMMARY")); assertFalse(it, it.contains(".jpg")) }
    }

    @Test fun aPhotoForgottenWhileTheModelRunsDiscardsTheResult() = runBlocking<Unit> {
        photo(0); scenes(); on()
        onModel = { forget.forgetMoments(listOf(mid(0))); onModel = {} }
        val r = pass().runIfDue(MS)!!
        assertEquals(0, r.written); assertTrue(r.rejected >= 1)
        assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun aStaleInputRevisionIsRefused() = runBlocking<Unit> {
        photo(0); photo(30); scenes(); on()
        val scene = ObjectRef(ObjectKind.SCENE, sid(0), 1)
        onModel = { audit.partRemoved(scene, AuditStep.BUILD, "MOMENT", mid(30)); onModel = {} } // the Scene's inputs change after its derive started
        val r = pass().runIfDue(MS)!!
        assertTrue(r.rejected >= 1)
        assertNull(audit.readDerived(scene, DerivedField.SUMMARY)?.takeIf { it.builtRev != audit.inputSeq(scene) })
    }

    @Test fun aFailingModelLeavesTheFieldMissingAndTheNextRunRetries() = runBlocking<Unit> {
        photo(0); scenes(); on(); fail = true
        val r = pass().runIfDue(MS)!!
        assertEquals(0, r.written); assertTrue(r.failed >= 1); assertEquals("", sceneText())
        fail = false
        val r2 = pass().runIfDue(MS + 6 * 60 * 60_000L)!!
        assertTrue(r2.written >= 1); assertTrue(sceneText().contains("REPLY-"))
    }

    @Test fun aRunIsBoundedByObjects() = runBlocking<Unit> {
        photo(0); photo(30); photo(60); scenes(); on()
        val r = pass(max = 2).runIfDue(MS)!!
        assertEquals(2, r.objects); assertEquals(4, prompts.size) // SUMMARY and CAPTION of two objects
    }

    @Test fun aForgetBetweenReadingTheInputsAndTheModelCallStopsTheCall() = runBlocking<Unit> {
        photo(0, "kept note"); photo(30, "FORGOTTEN-NOTE"); scenes(); on()
        inputs.testAfterRead = { inputs.testAfterRead = null; forget.forgetMoments(listOf(mid(30))) } // commits after the Scene's text was read
        pass().runIfDue(MS)
        assertTrue(prompts.isNotEmpty())
        prompts.forEach { assertFalse(it, it.contains("FORGOTTEN-NOTE")) }
    }

    @Test fun theKillSwitchIsReReadBeforeEveryCallAndNothingIsWrittenAfterItGoesOff() = runBlocking<Unit> {
        photo(0); photo(30); scenes(); on()
        onModel = { cadence.setEnabled(false); onModel = {} } // switched off during the first model call
        pass().runIfDue(MS)
        assertEquals(1, prompts.size); assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun everyModelCallNeedsItsOwnAdmissionAndTheFirstRefusalStopsThePass() = runBlocking<Unit> {
        photo(0); photo(30); scenes(); on(); refuseAdmitOnCall = 2
        pass().runIfDue(MS)
        assertEquals(1, prompts.size); assertEquals(2, admitCalls) // no third ask after the refusal
    }

    // --- a MARK Moment with no visible text gets no model call ---

    @Test fun aMarkMomentWithNoVisibleTextMakesNoModelCallAndABareMarkIsNotText() = runBlocking<Unit> {
        fx.mark("mk:1:1", t(0) + 100, "voice"); moments.build(listOf(mid(0))); scenes(); on()
        assertEquals(emptyList<String>(), inputs.lines(ObjectRef(ObjectKind.MOMENT, mid(0), 1)))
        val r = pass().runIfDue(MS)
        assertEquals(0, r!!.objects); assertTrue("no model call: $prompts", prompts.isEmpty())
    }

    @Test fun anIntentWordOfAMarkIsVisibleTextAndGivesOneCall() = runBlocking<Unit> {
        fx.mark("mk:1:1", t(0) + 100, "voice"); fx.mark("mk:1:1:i", t(0) + 100, "voice", "INTENT", "groceries"); moments.build(listOf(mid(0))); scenes(); on()
        assertEquals(listOf("groceries"), inputs.lines(ObjectRef(ObjectKind.MOMENT, mid(0), 1)))
        pass().runIfDue(MS)
        assertTrue(prompts.isNotEmpty() && prompts.all { it.contains("- groceries") })
    }

    @Test fun aPhotoMomentWithOnlyABareMarkLostTheMeaninglessMomentNote() = runBlocking<Unit> {
        photo(0, "cat on sofa"); fx.mark("mk:1:1", t(0) + 100, "tap"); moments.build(listOf(mid(0)))
        assertEquals(listOf("cat on sofa"), inputs.lines(ObjectRef(ObjectKind.MOMENT, mid(0), 1)))
    }
}
