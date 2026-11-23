package com.rokid.context.phone.ui.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<MemoryDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(port: MemoryForget) = MemoryDetailViewModel("m1", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, forget = port).also { vms += it }

    @Test fun doubleTapForgetRunsOnce() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean { calls.incrementAndGet(); gate.await(); return true }
            override suspend fun forgetMoment(frameId: String): Boolean { calls.incrementAndGet(); return true }
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT)
        assertEquals(ForgetKind.FACT, model.state.value.confirm)
        model.confirmForget(); model.confirmForget()
        withTimeout(5_000) { model.state.first { it.forgetting } }
        model.confirmForget()
        gate.complete(Unit)
        withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals(1, calls.get())
    }

    @Test fun aPhotoWithNoMemoryCanBeForgottenFromTheViewerThroughTheSamePort() = runBlocking {
        val forgotten = mutableListOf<String>()
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String) = false
            override suspend fun forgetMoment(frameId: String): Boolean { forgotten += frameId; return true }
        })
        model.askForgetPhoto("fr1")
        withTimeout(5_000) { model.state.first { it.found && it.confirm == ForgetKind.MOMENT && !it.forgetPreviewing } }
        model.confirmForget()
        withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals(listOf("fr1"), forgotten)
    }

    @Test fun failureShowsFixedCopy() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean = throw java.io.IOException("disk /data/x")
            override suspend fun forgetMoment(frameId: String): Boolean = true
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.message != null } }
        assertEquals("Couldn't forget that \u2014 try again", s.message)
        assertTrue(!s.forgetting && !s.forgotten)
    }

    @Test fun momentNeedsAFrame() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(MemoryForget.None)
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT)
        assertEquals(null, model.state.value.confirm)
    }

    /** GAP-1: an edit leaves the old text in the cached export; the hook (which deletes the cached exports) runs once per saved edit only. */
    @Test fun aSavedEditRunsTheEditHookAndARefusedOneDoesNot() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val edited = AtomicInteger()
        val model = MemoryDetailViewModel("m1", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, onTextEdited = { edited.incrementAndGet() }).also { vms += it }
        withTimeout(5_000) { model.state.first { it.found } }
        model.startEdit(); model.updateDraft("   "); model.saveEdit()
        assertEquals(0, edited.get())
        model.updateDraft("Keys in the drawer"); model.saveEdit()
        withTimeout(5_000) { model.state.first { it.message == "Saved" } }
        assertEquals(1, edited.get())
    }

    private fun objectPort(onObject: (ForgetKind, String) -> Boolean) = object : MemoryForget {
        override suspend fun forgetFact(memoryId: String) = false
        override suspend fun forgetMoment(frameId: String) = false
        override suspend fun forgetObject(kind: ForgetKind, id: String) = onObject(kind, id)
    }

    @Test fun aMarkForgetOpensTheDialogWithoutCountsAndRunsTheObjectForget() = runBlocking {
        for (kind in listOf(ForgetKind.MARK)) {
            val ran = mutableListOf<Pair<ForgetKind, String>>()
            val model = MemoryDetailViewModel("", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, forget = objectPort { k, id -> ran += k to id; true }).also { vms += it }
            model.askForgetObject(kind, "obj1")
            val s = withTimeout(5_000) { model.state.first { it.confirm == kind } }
            assertEquals(null, s.forgetPreview); assertTrue(!s.forgetPreviewing && s.forgetConfirmable)
            model.confirmForget()
            withTimeout(5_000) { model.state.first { it.forgotten } }
            assertEquals(listOf(kind to "obj1"), ran)
        }
    }

    @Test fun anObjectForgetNamesAnExportASaveOrAFailure() = runBlocking {
        val cases = listOf<Pair<() -> Boolean, String>>(
            { throw com.rokid.context.orchestration.DataOpGate.ExportInProgress() } to ForgetCopy.EXPORT_RUNNING,
            { throw com.rokid.context.orchestration.DataOpGate.WriteInProgress() } to ForgetCopy.SAVE_RUNNING,
            { throw CacheCleanupFailed() } to ForgetCopy.CACHE_LEFT,
            { false } to ForgetCopy.FAILED,
        )
        for ((fail, message) in cases) {
            val model = MemoryDetailViewModel("", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, forget = objectPort { _, _ -> fail() }).also { vms += it }
            model.askForgetObject(ForgetKind.MARK, "mk1"); model.confirmForget()
            val s = withTimeout(5_000) { model.state.first { !it.forgetting && it.confirm == null && it.message != null || it.forgotten } }
            assertEquals(message, s.message)
        }
    }

    @Test fun aForgetBlockedByACanvasSaveNamesTheSaveNotAnExport() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean = throw com.rokid.context.orchestration.DataOpGate.WriteInProgress()
            override suspend fun forgetMoment(frameId: String): Boolean = true
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.message != null } }
        assertEquals("A save is running. Try again in a minute, or when it finishes.", s.message)
        assertTrue(!s.forgetting && !s.forgotten)
    }

    @Test fun aForgetWhoseCachedExportCouldNotBeDeletedIsForgottenAndSaysWhatRemains() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean = throw CacheCleanupFailed()
            override suspend fun forgetMoment(frameId: String): Boolean = true
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals(ForgetCopy.CACHE_LEFT, s.message)
        assertTrue(ForgetCopy.CACHE_LEFT.contains("export file") && ForgetCopy.CACHE_LEFT.contains("next Forget"))
    }

    @Test fun aForgetBlockedByAnExportSaysSoAndWhenToRetry() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean = throw com.rokid.context.orchestration.DataOpGate.ExportInProgress()
            override suspend fun forgetMoment(frameId: String): Boolean = true
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.message != null } }
        assertEquals("An export is running. Try again in a minute, or when it finishes.", s.message)
        assertTrue(!s.forgetting && !s.forgotten)
    }

    // ---- v0.9.7 slice C: the Forget scope in the dialog (scope in plain words, counts first, the confirmed scope is the one that runs) ----
    private fun port(active: com.rokid.context.memory.forget.ForgetScope, preview: suspend () -> ForgetPreview?, onScoped: (ForgetKind, com.rokid.context.memory.forget.ForgetScope) -> Boolean = { _, _ -> true }) = object : MemoryForget {
        // the production port's plain forgetFact / forgetMoment are scoped by the SETTING too (used when no counts could be shown at the Photo scope)
        override suspend fun forgetFact(memoryId: String): Boolean = onScoped(ForgetKind.FACT, active)
        override suspend fun forgetMoment(frameId: String): Boolean = onScoped(ForgetKind.MOMENT, active)
        override fun activeScope() = active
        override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?) = preview()
        override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?) = onScoped(kind, scope)
    }
    private fun sceneCounts(requested: com.rokid.context.memory.forget.ForgetScope = com.rokid.context.memory.forget.ForgetScope.SCENE) = ForgetPreview(requested, requested, 4, 5, 2, 3, 1, 0)

    @Test fun theDialogLoadsTheCountsAndTheConfirmedScopeIsTheOneThatRuns() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        var ran: Pair<ForgetKind, com.rokid.context.memory.forget.ForgetScope>? = null
        // the setting moved to MOMENT after the dialog counted at SCENE: the delete must run at the scope the dialog SHOWED
        val model = vm(port(com.rokid.context.memory.forget.ForgetScope.MOMENT, { sceneCounts() }) { k, sc -> ran = k to sc; true })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT)
        val open = withTimeout(5_000) { model.state.first { it.forgetPreview != null } }
        assertEquals(sceneCounts(), open.forgetPreview); assertTrue(open.forgetConfirmable)
        model.confirmForget(); withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals(ForgetKind.MOMENT to com.rokid.context.memory.forget.ForgetScope.SCENE, ran)
    }

    // Codex pin r1 #1: a photo Forget never deletes a pin the dialog did not name; a failed preview deletes none.
    private fun pinPort(preview: suspend () -> ForgetPreview?, onPins: (Set<String>?) -> Unit) = object : MemoryForget {
        override suspend fun forgetFact(memoryId: String) = true
        override suspend fun forgetMoment(frameId: String): Boolean { onPins(null); return true } // the production forgetMoment passes emptySet(): no pin
        override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?) = preview()
        override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?): Boolean { onPins(emptySet()); return true }
        override suspend fun forgetScopedPins(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?, confirmedPins: Set<String>): Boolean { onPins(confirmedPins); return true }
    }

    @Test fun aPhotoForgetPassesExactlyThePinsThePreviewNamedAndAFailedPreviewPassesNone() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        var got: Set<String>? = setOf("unset")
        val ok = vm(pinPort({ sceneCounts(com.rokid.context.memory.forget.ForgetScope.PHOTO).copy(pins = 1, pinNames = listOf("Home"), pinIds = setOf("pin-1")) }, { got = it }))
        withTimeout(5_000) { ok.state.first { it.found } }
        ok.askForget(ForgetKind.MOMENT); withTimeout(5_000) { ok.state.first { it.forgetPreview != null } }
        ok.confirmForget(); withTimeout(5_000) { ok.state.first { it.forgotten } }
        assertEquals(setOf("pin-1"), got)

        // the pin lookup failed inside a successful preview: the pins are unknown, none is passed
        got = setOf("unset")
        val unknown = vm(pinPort({ sceneCounts(com.rokid.context.memory.forget.ForgetScope.PHOTO).copy(pinIds = setOf("pin-1"), pinsUnknown = true) }, { got = it }))
        withTimeout(5_000) { unknown.state.first { it.found } }
        unknown.askForget(ForgetKind.MOMENT); withTimeout(5_000) { unknown.state.first { it.forgetPreview != null } }
        unknown.confirmForget(); withTimeout(5_000) { unknown.state.first { it.forgotten } }
        assertEquals(emptySet<String>(), got)

        // the preview threw (Photo scope still confirms): the plain forgetMoment runs, whose production port arms no pin
        got = setOf("unset")
        val failed = vm(pinPort({ throw IllegalStateException("x") }, { got = it }))
        withTimeout(5_000) { failed.state.first { it.found } }
        failed.askForget(ForgetKind.MOMENT); withTimeout(5_000) { failed.state.first { it.forgetPreviewFailed } }
        failed.confirmForget(); withTimeout(5_000) { failed.state.first { it.forgotten } }
        assertEquals(null, got)
    }

    @Test fun productionMomentForgetNamesNoPinAndTheScopedOneCarriesTheConfirmedIds() {
        val dir = java.io.File(System.getProperty("user.dir"))
        val root = generateSequence(dir) { it.parentFile }.first { java.io.File(it, "phone/src/main/java").isDirectory }
        val src = java.io.File(root, "phone/src/main/java/com/rokid/context/phone/ui/memory/ProductionForget.kt").readText()
        assertTrue(src.lines().single { "override suspend fun forgetMoment" in it }.contains("confirmedPins = emptySet()"))
        assertTrue(src.contains("confirmedPins = confirmedPins"))
    }

    @Test fun aLargeRebuildNeedsTheTickAndSendsTheAcknowledgedToken() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        var sent: String? = null
        val port = object : MemoryForget {
            override suspend fun forgetFact(memoryId: String) = true
            override suspend fun forgetMoment(frameId: String) = true
            override fun activeScope() = com.rokid.context.memory.forget.ForgetScope.SCENE
            override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?) = sceneCounts().copy(planHash = "h", needsAck = true, ackHash = "h+ack", peerResults = 250)
            override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?): Boolean { sent = planHash; return true }
        }
        val model = vm(port)
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT)
        assertTrue(!withTimeout(5_000) { model.state.first { it.forgetPreview != null } }.forgetConfirmable)
        model.confirmForget(); assertEquals("not without the tick", null, sent)
        model.acknowledgeForget(true); assertTrue(withTimeout(5_000) { model.state.first { it.forgetConfirmable } }.forgetAcked)
        model.confirmForget(); withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals("h+ack", sent)
    }

    @Test fun theConfirmIsRefusedWhileTheCountsAreStillBeingCounted() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        val gate = CompletableDeferred<ForgetPreview?>(); var ran = 0
        val model = vm(port(com.rokid.context.memory.forget.ForgetScope.SCENE, { gate.await() }) { _, _ -> ran++; true })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT)
        val s = withTimeout(5_000) { model.state.first { it.confirm != null && it.forgetPreviewing } }
        assertTrue(!s.forgetConfirmable)
        model.confirmForget(); assertEquals("nothing ran, the dialog is still open", 0, ran); assertEquals(ForgetKind.MOMENT, model.state.value.confirm)
        gate.complete(sceneCounts()); withTimeout(5_000) { model.state.first { it.forgetConfirmable } }
        model.confirmForget(); withTimeout(5_000) { model.state.first { it.forgotten } }; assertEquals(1, ran)
    }

    @Test fun aFailedCountBlocksMomentAndSceneButNotThePhotoScope() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        com.rokid.context.memory.forget.ForgetScope.values().forEach { sc ->
            var ran = 0
            val model = vm(port(sc, { throw java.io.IOException("db") }) { _, _ -> ran++; true })
            withTimeout(5_000) { model.state.first { it.found } }
            model.askForget(ForgetKind.FACT)
            val s = withTimeout(5_000) { model.state.first { it.confirm != null && !it.forgetPreviewing } }
            assertEquals("$sc blocked", sc != com.rokid.context.memory.forget.ForgetScope.PHOTO, !s.forgetConfirmable); assertTrue(s.forgetPreviewFailed)
            model.confirmForget()
            if (sc == com.rokid.context.memory.forget.ForgetScope.PHOTO) { withTimeout(5_000) { model.state.first { it.forgotten } }; assertEquals(1, ran) } else assertEquals("$sc: nothing ran", 0, ran)
        }
    }

    @Test fun dismissingTheDialogDropsTheCountsAndALateCountDoesNotReopenIt() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        val gate = CompletableDeferred<ForgetPreview?>()
        val model = vm(port(com.rokid.context.memory.forget.ForgetScope.MOMENT, { gate.await() }))
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT); model.dismissForget(); gate.complete(sceneCounts(com.rokid.context.memory.forget.ForgetScope.MOMENT))
        kotlinx.coroutines.delay(100)
        assertEquals(null, model.state.value.confirm); assertEquals(null, model.state.value.forgetPreview)
    }

    @Test fun aPortWithNoPreviewKeepsTheOldUnscopedPath() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1")); var fact = 0
        val model = vm(object : MemoryForget { override suspend fun forgetFact(memoryId: String): Boolean { fact++; return true }; override suspend fun forgetMoment(frameId: String) = true })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); assertTrue(model.state.value.forgetConfirmable); assertEquals(null, model.state.value.forgetPreview)
        model.confirmForget(); withTimeout(5_000) { model.state.first { it.forgotten } }; assertEquals(1, fact)
    }

    @Test fun aTooLargePreviewBlocksTheConfirmAtEveryScope() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1")); var ran = 0
        val model = vm(port(com.rokid.context.memory.forget.ForgetScope.PHOTO, { sceneCounts().copy(tooLarge = true) }) { _, _ -> ran++; true })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT); val s = withTimeout(5_000) { model.state.first { it.forgetPreview != null } }
        assertTrue(!s.forgetConfirmable); model.confirmForget(); assertEquals(0, ran); assertEquals(ForgetKind.MOMENT, model.state.value.confirm)
    }

    @Test fun aContentChangeSinceThePreviewSaysSoReopensTheDialogWithNewCountsAndDeletedNothing() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        var previews = 0; var scoped = 0; val hashes = mutableListOf<String?>()
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String) = true
            override suspend fun forgetMoment(frameId: String) = true
            override fun activeScope() = com.rokid.context.memory.forget.ForgetScope.SCENE
            override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?) = sceneCounts().copy(photos = 4 + previews++, planHash = "h$previews")
            override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?): Boolean { hashes += planHash; if (scoped++ == 0) throw ContentChanged(); return true }
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.confirm == ForgetKind.MOMENT && it.forgetPreview?.photos == 5 } }
        assertEquals(ForgetCopy.CONTENT_CHANGED, s.message); assertEquals("Content changed since the preview. Review again.", ForgetCopy.CONTENT_CHANGED); assertTrue(!s.forgotten && !s.forgetting)
        model.confirmForget(); withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals("the delete is pinned to the hash of the counts the user saw", listOf<String?>("h1", "h2"), hashes)
    }
}
