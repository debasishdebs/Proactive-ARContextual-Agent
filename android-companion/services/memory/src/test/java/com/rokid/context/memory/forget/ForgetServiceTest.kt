package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.db.SummaryTraceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetServiceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val proposals = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val service = ForgetService(db, proposals) { 1_000 }

    @After fun close() = db.close()

    private suspend fun mem(id: String, frameId: String? = null, derived: Boolean = false, text: String = "text of $id", review: ReviewState = ReviewState.OK) =
        db.memoryDao().insert(MemoryEntity(id, frameId, text, "note", "", MemoryTier.SHORT, 0.8, review, false, 1, derived, text))

    private suspend fun edge(child: String, parent: String) = db.forgetDao().insertEdges(listOf(MemorySourceEntity(child, parent)))
    private suspend fun state(id: String) = db.memoryViewDao().byId(id)?.reviewState

    @Test fun transitiveClosureInvalidatesBAndC() = runBlocking {
        mem("A"); mem("B", derived = true); mem("C", derived = true)
        edge("B", "A"); edge("C", "B")
        val r = service.forgetFact("A")
        assertEquals(setOf("A"), r.forgotten)
        assertEquals(setOf("B", "C"), r.invalidated)
        assertNull(db.memoryViewDao().byId("A"))
        assertEquals(ReviewState.INVALIDATED, state("B")); assertEquals(ReviewState.INVALIDATED, state("C"))
        // every edge touching A is gone; B→… edges stay for the re-summarise worker
        assertTrue(db.forgetDao().edgesTo(listOf("A")).isEmpty())
        assertEquals(1, db.forgetDao().edgesTo(listOf("B")).size)
    }

    @Test fun diamondInvalidatesOnce() = runBlocking {
        listOf("A", "B", "C", "D").forEach { mem(it) }
        edge("B", "A"); edge("C", "A"); edge("D", "B"); edge("D", "C")
        assertEquals(setOf("B", "C", "D"), service.forgetFact("A").invalidated)
    }

    @Test fun cycleTerminates() = runBlocking {
        listOf("A", "B", "C").forEach { mem(it) }
        edge("B", "A"); edge("C", "B"); edge("B", "C")
        assertEquals(setOf("B", "C"), service.forgetFact("A").invalidated)
    }

    @Test fun closureOver999Ids() = runBlocking {
        mem("A")
        // a wide fan-out (1 500 children of A) and a long chain hanging off one of them
        val kids = (0 until 1_500).map { "k$it" }
        kids.forEach { mem(it, derived = true) }
        db.forgetDao().insertEdges(kids.map { MemorySourceEntity(it, "A") })
        val r = service.forgetFacts(listOf("A") + (0 until 1_200).map { "unknown$it" })
        assertEquals(1_500, r.invalidated.size)
        assertTrue(kids.all { state(it) == ReviewState.INVALIDATED })
        // and forgetting 1 500 ids at once (all IN lists chunked)
        val r2 = service.forgetFacts(kids)
        assertEquals(1_500, r2.forgotten.size)
        assertEquals(0, db.memoryDao().count())
    }

    @Test fun forgetFactTombstonesFrameAndKeepsSiblings() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("f1", 1, "/f1.jpg", "rokid_glasses"))
        mem("m1", frameId = "f1"); mem("m2", frameId = "f1")
        val r = service.forgetFact("m1")
        assertEquals(ReviewState.OK, state("m2"))
        assertEquals(listOf("f1"), db.forgetDao().tombstoned(listOf("f1")))
        assertTrue(r.framesToDelete.isEmpty())
        assertTrue(db.forgetDao().frame("f1") != null)
    }

    @Test fun lastMemoryOfFrameListsJpeg() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("f1", 1, "/f1.jpg", "rokid_glasses"))
        mem("m1", frameId = "f1")
        assertEquals(listOf("/f1.jpg"), service.forgetFact("m1").framesToDelete)
    }

    @Test fun forgetMomentDeletesFrameAndAllMemories() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("f1", 1, "/f1.jpg", "rokid_glasses"))
        mem("m1", frameId = "f1"); mem("m2", frameId = "f1"); mem("other")
        val r = service.forgetMoment("f1")
        assertEquals(setOf("m1", "m2"), r.forgotten)
        assertEquals(setOf("/f1.jpg", "/f1.meta.json"), r.framesToDelete.toSet())
        assertNull(db.forgetDao().frame("f1"))
        assertEquals(0, db.forgetDao().memoryIdsOfFrame("f1").size)
        assertEquals(listOf("f1"), db.forgetDao().tombstoned(listOf("f1")))
        assertEquals(1, db.memoryDao().count())
    }

    @Test fun unknownIdsGiveAnEmptyResult() = runBlocking {
        assertEquals(ForgetService.Result.EMPTY, service.forgetFact("nope"))
        assertEquals(ForgetService.Result.EMPTY, service.forgetMoment("nope"))
    }

    @Test fun asksAndNudgesCitingClosureRedacted() = runBlocking {
        mem("A"); mem("C", derived = true); mem("A1")
        edge("C", "A")
        db.askDao().upsert(AskEntity("q1", 1, "q", "answer from C", """["C"]""", null, "phone_typed", false))
        db.askDao().upsert(AskEntity("q2", 1, "q", "answer from A1", """["A1"]""", null, "phone_typed", false))
        db.nudgeDao().insertIgnore(NudgeEntity("n1", 1, "nudge from A", "why A", """["A"]""", 1, "h", "phone", false))
        val r = service.forgetFact("A")
        assertEquals(1, r.redactedAsks); assertEquals(1, r.redactedNudges)
        val q1 = db.askDao().byId("q1")!!
        assertEquals(ForgetService.REDACTED, q1.answer); assertTrue(q1.redacted); assertEquals("[]", q1.sourcesJson)
        val q2 = db.askDao().byId("q2")!!
        assertFalse(q2.redacted); assertEquals("answer from A1", q2.answer)
        db.query("SELECT text, why, sourceIdsJson, redacted FROM nudge", null).use { c -> c.moveToFirst(); assertEquals(ForgetService.REDACTED, c.getString(0)); assertEquals("", c.getString(1)); assertEquals("[]", c.getString(2)); assertEquals(1, c.getInt(3)) }
    }

    @Test fun pendingProposalOfRedactedAskExpires() = runBlocking {
        mem("A")
        db.askDao().upsert(AskEntity("q1", 1, "q", "a", """["A"]""", null, "phone_typed", false))
        proposals.create(ProposalEntity("p1", ProposalOrigin.ASK.name, "q1", "REMINDER", "{}", ProposalState.PENDING.name, 99_999, null, null, 1), 1)
        service.forgetFact("A")
        assertEquals(ProposalState.EXPIRED.name, proposals.byId("p1")!!.state)
    }

    @Test fun vectorsRemovedFromAllSpaces() = runBlocking {
        mem("A"); mem("B")
        edge("B", "A")
        val e = db.embeddingDao()
        listOf("s1", "s2").forEachIndexed { i, s ->
            db.query("INSERT INTO embedding_space VALUES ('$s','fp$i','{}',1,'${if (i == 0) SpaceState.ACTIVE else SpaceState.BUILDING}','{}',$i)", null).close()
            listOf("A", "B").forEach { m -> e.putVector(MemoryVectorEntity(m, s, byteArrayOf(1))) }
        }
        service.forgetFact("A")
        assertTrue(e.vectors("s1").isEmpty() && e.vectors("s2").isEmpty())
    }

    @Test fun traceNotesHoldNoMemoryText() = runBlocking {
        mem("A", text = "Secret diary on the desk")
        db.traceDao().upsert(SummaryTraceEntity("t1", "TRUSTED", 1, "frames=1 facts=1 v1"))
        service.forgetFact("A")
        assertTrue(db.traceDao().recent(10).none { "Secret" in it.note || "diary" in it.note })
        assertEquals(1, db.traceDao().recent(10).size) // trace notes are untouched
    }

    @Test fun forgottenTextLeavesTheFtsIndex() = runBlocking {
        mem("A", text = "zebra umbrella")
        service.forgetFact("A")
        assertTrue(db.memoryDao().search("zebra*", 5).isEmpty())
    }

    /** Fix round 1 C1: retention expiry deletes only the expired rows; derived memories keep their own windows. */
    @Test fun expireLeavesDescendantsAlone() = runBlocking {
        mem("A"); mem("B", derived = true)
        edge("B", "A")
        db.askDao().upsert(AskEntity("q1", 1, "q", "from A", """["A"]""", null, "phone_typed", false))
        db.askDao().upsert(AskEntity("q2", 1, "q", "from B", """["B"]""", null, "phone_typed", false))
        val r = service.expire(listOf("A"))
        assertEquals(setOf("A"), r.forgotten)
        assertTrue(r.invalidated.isEmpty())
        assertEquals(ReviewState.OK, state("B"))
        assertTrue(db.forgetDao().edgesFrom(listOf("B")).isEmpty())
        assertTrue(db.askDao().byId("q1")!!.redacted)
        assertFalse(db.askDao().byId("q2")!!.redacted)
    }

    /** Fix round 1 I2: executing/executed proposals of a redacted origin lose their params and log details. */
    @Test fun executedProposalOfRedactedAskIsScrubbed() = runBlocking {
        mem("A")
        db.askDao().upsert(AskEntity("q1", 1, "q", "a", """["A"]""", null, "phone_typed", false))
        listOf("p1" to ProposalState.EXECUTED, "p2" to ProposalState.EXECUTING, "p3" to ProposalState.DECLINED).forEach { (id, st) ->
            proposals.create(ProposalEntity(id, ProposalOrigin.ASK.name, "q1", "REMINDER", """{"title":"Call mom","dueAt":5}""", st.name, 99_999, null, null, 1), 1)
            db.actionDao().log(com.rokid.context.memory.db.ActionLogEntity(ts = 2, proposalId = id, transition = "X", detail = "Call mom"))
        }
        service.forgetFact("A")
        listOf("p1", "p2", "p3").forEach { id ->
            assertEquals(ForgetService.REDACTED_PARAMS, proposals.byId(id)!!.paramsJson)
            assertTrue(db.actionDao().logFor(id).none { "Call mom" in it.detail })
        }
        assertEquals(ProposalState.EXECUTED.name, proposals.byId("p1")!!.state) // state kept: Undo still works by createdRef
    }

    /** Final review C1: retention expiry leaves accepted (EXECUTING/EXECUTED) proposals whole; the rest are scrubbed. */
    @Test fun expireLeavesAcceptedProposalsAlone() = runBlocking {
        mem("A")
        db.askDao().upsert(AskEntity("q1", 1, "q", "a", """["A"]""", null, "phone_typed", false))
        val params = """{"title":"Call mom","dueAt":5}"""
        listOf("p1" to ProposalState.EXECUTED, "p2" to ProposalState.EXECUTING, "p3" to ProposalState.PENDING, "p4" to ProposalState.DECLINED).forEach { (id, st) ->
            proposals.create(ProposalEntity(id, ProposalOrigin.ASK.name, "q1", "REMINDER", params, st.name, 99_999, null, null, 1), 1)
            db.actionDao().log(com.rokid.context.memory.db.ActionLogEntity(ts = 2, proposalId = id, transition = "X", detail = "Call mom"))
        }
        val r = service.expire(listOf("A"))
        assertTrue(db.askDao().byId("q1")!!.redacted)
        assertEquals(setOf("p3", "p4"), r.redactedProposals.toSet())
        listOf("p1", "p2").forEach { id ->
            assertEquals(params, proposals.byId(id)!!.paramsJson)
            assertTrue(db.actionDao().logFor(id).any { "Call mom" in it.detail })
        }
        assertEquals(ProposalState.EXECUTED.name, proposals.byId("p1")!!.state)
        assertEquals(ProposalState.EXPIRED.name, proposals.byId("p3")!!.state)
        listOf("p3", "p4").forEach { id ->
            assertEquals(ForgetService.REDACTED_PARAMS, proposals.byId(id)!!.paramsJson)
            assertTrue(db.actionDao().logFor(id).none { "Call mom" in it.detail })
        }
    }

    // --- wave 3 Task 11 (S1, S2): photo cites and metadata-only moments -----------------------------------------------

    private suspend fun keyframe(id: String, withFrame: Boolean = true, vector: Boolean = true, state: String = "NEAR_DUP") {
        if (withFrame) db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
        db.frameStateDao().insertIgnore(listOf(com.rokid.context.memory.db.FrameStateEntity(id, state, 0, null, null, null, 1)))
        if (vector) db.frameVectorDao().upsert(listOf(com.rokid.context.memory.db.FrameVectorEntity(id, "s1", byteArrayOf(1, 2, 3, 4))))
    }

    @Test fun forgetMomentRedactsSavedAnswerCitingPhoto() = runBlocking {
        keyframe("f1"); mem("m1", frameId = "f1")
        db.askDao().upsert(AskEntity("q1", 1, "where?", "On the desk (photo)", """["${PHOTO_CITE_PREFIX}f1"]""", null, "phone_typed", false))
        db.askDao().upsert(AskEntity("q2", 1, "q", "unrelated", """["${PHOTO_CITE_PREFIX}f10"]""", null, "phone_typed", false)) // "f10" never matches "f1"
        proposals.create(ProposalEntity("p1", ProposalOrigin.ASK.name, "q1", "REMINDER", """{"title":"Call mom","dueAt":5}""", ProposalState.EXECUTED.name, 99_999, null, null, 1), 1)
        db.actionDao().log(com.rokid.context.memory.db.ActionLogEntity(ts = 2, proposalId = "p1", transition = "X", detail = "Call mom"))
        val r = service.forgetMoment("f1")
        assertEquals(1, r.redactedAsks)
        assertTrue("alarm owner listed for cancellation", "p1" in r.redactedProposals)
        val q1 = db.askDao().byId("q1")!!
        assertTrue(q1.redacted); assertEquals(ForgetService.REDACTED, q1.answer); assertEquals("[]", q1.sourcesJson)
        assertFalse(db.askDao().byId("q2")!!.redacted)
        assertEquals(ForgetService.REDACTED_PARAMS, proposals.byId("p1")!!.paramsJson)
        assertTrue(db.actionDao().logFor("p1").none { "Call mom" in it.detail })
        // export reads the redacted row
        assertTrue(db.exportDao().askPage(0, 10).filter { it.id == "q1" }.all { it.answer == ForgetService.REDACTED })
    }

    /** Wave 3 Task 3 cites the CAPTION MEMORY id (not `frame:<id>`) for every captioned photo: Forget of the moment must redact those answers too. */
    @Test fun forgetMomentRedactsAskCitingTheCaptionMemoryId() = runBlocking {
        keyframe("f1"); mem("cap1", frameId = "f1")
        db.askDao().upsert(AskEntity("q1", 1, "where?", "On the desk", """["cap1"]""", null, "phone_typed", false))
        db.askDao().upsert(AskEntity("q2", 1, "q", "unrelated", """["cap10"]""", null, "phone_typed", false))
        val r = service.forgetMoment("f1")
        assertEquals(1, r.redactedAsks)
        assertTrue(db.askDao().byId("q1")!!.redacted); assertFalse(db.askDao().byId("q2")!!.redacted)
    }

    @Test fun expiredUncaptionedSkippedPhotoCanBeForgotten() = runBlocking {
        keyframe("f1"); db.askDao().upsert(AskEntity("q1", 1, "q", "a skipped photo", """["${PHOTO_CITE_PREFIX}f1"]""", null, "phone_typed", false))
        service.expirePhotos(listOf("f1")) // retention: frame row gone, no memory, meta and vector kept
        assertNull(db.forgetDao().frame("f1")); assertEquals(1, db.frameMetaDao().byIds(listOf("f1")).size)
        val r = service.forgetMoment("f1")
        assertEquals(1, r.redactedAsks)
        assertTrue(db.frameMetaDao().byIds(listOf("f1")).isEmpty()); assertNull(db.frameStateDao().get("f1"))
        assertTrue(db.frameVectorDao().forFrames("s1", listOf("f1")).isEmpty())
        assertEquals(listOf("f1"), db.forgetDao().tombstoned(listOf("f1")))
    }

    @Test fun forgetMomentOfMetaOnlyAndVectorOnlyMoments() = runBlocking {
        keyframe("meta", withFrame = false, vector = false)
        service.forgetMoment("meta")
        assertTrue(db.frameMetaDao().byIds(listOf("meta")).isEmpty())
        db.frameVectorDao().upsert(listOf(com.rokid.context.memory.db.FrameVectorEntity("vec", "s1", byteArrayOf(1, 2, 3, 4))))
        service.forgetMoment("vec")
        assertTrue(db.frameVectorDao().forFrames("s1", listOf("vec")).isEmpty())
    }

    @Test fun forgetMomentEmptyOnlyWhenNothingExists() = runBlocking {
        assertEquals(ForgetService.Result.EMPTY, service.forgetMoment("nothing"))
        assertTrue(db.forgetDao().tombstoned(listOf("nothing")).isEmpty())
        db.askDao().upsert(AskEntity("q1", 1, "q", "a", """["${PHOTO_CITE_PREFIX}nothing"]""", null, "phone_typed", false))
        // user ruling (safer default): a stray cite of an id with nothing left is still redacted, no EMPTY short-circuit before redaction
        val r = service.forgetMoment("nothing")
        assertEquals(1, r.redactedAsks); assertTrue(db.askDao().byId("q1")!!.redacted)
        assertEquals(ForgetService.Result.EMPTY, service.forgetMoment("nothing")) // and nothing is left to do the second time
    }

    @Test fun forgetMomentListsTheSidecarToo() = runBlocking {
        keyframe("f1")
        assertEquals(setOf("/f1.jpg", "/f1.meta.json"), service.forgetMoment("f1").framesToDelete.toSet())
    }

    @Test fun forgetMomentDeletesFrameSourceRowsByFrame() = runBlocking {
        // an orphan provenance row (its memory is already gone) and an orphan canvas row of a moment that has nothing else left
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("gone_memory", "f9", null, "FACT"), FrameSourceEntity("gone_memory", "other", null, "FACT")))
        service.forgetMoment("f9")
        assertTrue(db.frameSourceDao().memoriesForFrame("f9").isEmpty())
        assertEquals(listOf("gone_memory"), db.frameSourceDao().memoriesForFrame("other"))
        keyframe("f1"); db.frameSourceDao().insertAll(listOf(FrameSourceEntity("gone2", "f1", null, "CLUSTER_MEMBER")))
        service.forgetMoment("f1")
        assertTrue(db.frameSourceDao().memoriesForFrame("f1").isEmpty())
    }

    @Test fun expiryVariantOfForgetMomentNeverRedactsPhotoCites() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("legacy", 1, "/legacy.jpg", "rokid_glasses"))
        db.askDao().upsert(AskEntity("q1", 1, "q", "answer", """["${PHOTO_CITE_PREFIX}legacy"]""", null, "phone_typed", false))
        val r = service.forgetMoment("legacy", redactCites = false)
        assertEquals(listOf("/legacy.jpg", "/legacy.meta.json"), r.framesToDelete.sorted())
        assertFalse(db.askDao().byId("q1")!!.redacted)
        // the nothing-left branch too: cites stay
        db.askDao().upsert(AskEntity("q2", 1, "q", "answer", """["${PHOTO_CITE_PREFIX}ghost"]""", null, "phone_typed", false))
        service.forgetMoment("ghost", redactCites = false)
        assertFalse(db.askDao().byId("q2")!!.redacted)
    }

    // --- wave 3 Task 2 (S3, S4): a photo the user described leaves the skipped-photo rule ---------------------------------

    private suspend fun skippedDue() = db.forgetDao().skippedPhotosDue(0, 0, 1_000, 0, 10)
    private suspend fun linkedDue() = db.forgetDao().linkedPhotosDue(0, 0, 1_000, 0, 0, 0, 10)
    private suspend fun described(frameId: String) {
        mem("rokid_desc_d_$frameId", frameId = frameId)
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("rokid_desc_d_$frameId", frameId, null, "FACT")))
    }

    // codex r11: only an OK, non-derived, non-canvas description makes a photo deletable; a canvas save or a fact awaiting review never does
    private suspend fun cite(memoryId: String, frameId: String, role: String = "FACT") =
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity(memoryId, frameId, null, role)))

    // codex r12 P1-1: a scene summary describes only the representatives it was sent; a member link never authorises deleting a member photo
    @Test fun aClusterMemberLinkNeverDescribesTheMember() = runBlocking {
        keyframe("member"); keyframe("rep")
        mem("rokid_cl_k1_0", frameId = "rep")                       // OK, non-derived scene summary (memory.frameId = the medoid)
        cite("rokid_cl_k1_0", "rep", "CLUSTER_REP"); cite("rokid_cl_k1_0", "member", "CLUSTER_MEMBER")
        mem("rokid_cv_r1_0", frameId = "member"); cite("rokid_cv_r1_0", "member")   // an excluded canvas citation of the member
        assertEquals("only the delivered representative is described", listOf("rep"), linkedDue())
        assertTrue("the member stays an ordinary skipped photo: the canvas row does not exempt it", "member" in skippedDue())
        assertEquals("the expiry recheck uses the same rule", 0, service.expirePhotos(listOf("member"), requireLinked = true).expiredFrames)
        assertEquals(1, service.expirePhotos(listOf("rep"), requireLinked = true).expiredFrames)
    }

    // codex r12 P2-3: a photo only excluded rows cite is still describable (night queue) and counted as stranded until then
    @Test fun aPhotoOnlyExcludedRowsCiteIsDescribableAndCounted() = runBlocking {
        keyframe("cv", state = "EXTRACTED"); keyframe("der", state = "EXTRACTED"); keyframe("ok", state = "EXTRACTED")
        mem("rokid_cv_r1_0", frameId = "cv"); cite("rokid_cv_r1_0", "cv")
        mem("rokid_mem_rs_1", frameId = "der", derived = true); cite("rokid_mem_rs_1", "der")
        mem("rokid_kf_k1_c0", frameId = "ok"); cite("rokid_kf_k1_c0", "ok")
        assertEquals(listOf("cv", "der"), db.forgetDao().describeDue(0, 0, 1_000, 0, 0, 10).sorted())
        assertEquals(2, db.forgetDao().strandedPhotos())
        assertEquals(1, db.frameStateDao().queueForDescribe("cv", 5))
        assertEquals("a described photo is never queued again", 0, db.frameStateDao().queueForDescribe("ok", 5))
    }

    @Test fun retentionCountsOnlyOkExtractionDescriptions() = runBlocking {
        keyframe("c"); keyframe("r"); keyframe("e"); keyframe("d")
        mem("rokid_cv_run1_0", frameId = "c")                                                   // a canvas save, even OK
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("rokid_cv_run1_0", "c", null, "FACT")))
        mem("rokid_kf_k1_0", frameId = "r", review = ReviewState.NEEDS_REVIEW)                    // an extraction fact still awaiting review
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("rokid_kf_k1_0", "r", null, "FACT")))
        mem("rokid_kf_k2_c0", frameId = "e")                                                    // an OK extraction caption
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("rokid_kf_k2_c0", "e", null, "FACT")))
        mem("rokid_mem_rs_1", frameId = "d", derived = true)                                     // a re-summary of other memories
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("rokid_mem_rs_1", "d", null, "FACT")))
        assertEquals(listOf("e"), linkedDue())
    }

    @Test fun describedFactRowTakesAPhotoOutOfTheSkippedRule() = runBlocking {
        keyframe("f1"); keyframe("f2"); keyframe("f3")
        assertEquals(listOf("f1", "f2", "f3"), skippedDue())
        described("f1")
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("cluster_summary", "f2", "c1", "CLUSTER_MEMBER"))) // a cluster summary citing it is not a describe
        assertEquals("only a FACT row exempts", listOf("f2", "f3"), skippedDue())
        assertTrue("it follows the photo window now", "f1" in linkedDue())
    }

    @Test fun expirePhotosRecheckSkippedKeepsAPhotoDescribedAfterSelection() = runBlocking {
        keyframe("f1"); keyframe("f2")
        val selected = skippedDue()
        assertEquals(listOf("f1", "f2"), selected)
        described("f1") // the user's describe commits after retention selected the ids
        val kept = service.expirePhotos(selected, recheckSkipped = true)
        assertEquals(setOf("/f2.jpg", "/f2.meta.json"), kept.framesToDelete.toSet())
        assertTrue("described photo kept", db.forgetDao().frame("f1") != null); assertTrue(db.forgetDao().tombstoned(listOf("f1")).isEmpty())
        assertNull(db.forgetDao().frame("f2"))
    }

    @Test fun expirePhotosWithoutRecheckStillExpiresWhatItIsGiven() = runBlocking {
        keyframe("f1"); described("f1")
        service.expirePhotos(listOf("f1"))
        assertNull(db.forgetDao().frame("f1"))
    }
}
