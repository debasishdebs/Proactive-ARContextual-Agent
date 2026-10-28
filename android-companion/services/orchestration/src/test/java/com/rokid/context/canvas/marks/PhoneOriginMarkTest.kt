package com.rokid.context.canvas.marks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkRef
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 slice D: a mark from the phone (voice or the Home chat) is recorded at once and idempotently, and asks the glasses for its photo (or says why not). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneOriginMarkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val epoch = DeletionEpoch("inst", 2, 1_000)
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var dao: com.rokid.context.memory.db.CanvasSlice3Dao
    private val t0 = 1_761_000_000_000L
    private val requests = ArrayList<String>()
    private var reason: String? = null
    private var sendOk = true
    private var call = false
    private val forgotten = ArrayList<String>()
    private val requester = object : PhonePhotoRequester {
        override fun reasonNow(): String? = reason
        override suspend fun request(markRef: String): Boolean { requests += markRef; return sendOk }
    }
    private lateinit var rec: MarkRecorder

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        db = MemoryGraph.get(ctx).db; dao = db.canvasSlice3Dao()
        rec = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 }, {}, forgetPhoto = { forgotten += it; true }, phonePhoto = requester, callActive = { call })
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun chat(vararg c: VoiceMark, at: Long = t0) = rec.applyVoice(c.toList(), MarkOrigin.PHONE_CHAT, at, rec.phonePhotoDecision())
    private suspend fun voice(vararg c: VoiceMark, at: Long = t0) = rec.applyVoice(c.toList(), MarkOrigin.PHONE_VOICE, at, rec.phonePhotoDecision())

    @Test fun aChatMarkIsRecordedPendingAndTheGlassesAreAskedOnceForItsPhoto() = runBlocking<Unit> {
        chat(VoiceMark.Mark(null))
        val l = dao.labels().single()
        assertEquals(MarkRef.labelId(MarkRef.of("phone", t0)), l.labelId)
        assertEquals("phone_chat", l.source); assertEquals(MarkPhotoReason.PENDING, l.photo)
        assertEquals(listOf("phone:$t0"), requests)
    }

    @Test fun theSameMarkTwiceIsOneMarkAndOneRequest() = runBlocking<Unit> {
        chat(VoiceMark.Mark("driving")); chat(VoiceMark.Mark("driving"))
        assertEquals(setOf("mk:phone:$t0", "mk:phone:$t0:i"), dao.labels().map { it.labelId }.toSet())
        assertEquals(1, requests.size)
    }

    @Test fun aVoiceMarkOnThePhoneAsksForAPhotoToo() = runBlocking<Unit> {
        rec.onPhoneListenStart(t0)
        voice(VoiceMark.Mark(null), at = t0 + 2_000)
        assertEquals("phone_voice", dao.labels().single().source); assertEquals(listOf("phone:$t0"), requests)
    }

    @Test fun aSecondChatMarkIsNeverMergedIntoTheEarlierOne() = runBlocking<Unit> {
        chat(VoiceMark.Mark(null), at = t0); chat(VoiceMark.Mark(null), at = t0 + 60_000)
        assertEquals(2, dao.labels().size); assertEquals(2, requests.size)
    }

    @Test fun whenThePhoneKnowsThereIsNoPhotoItRecordsTheReasonAndAsksNobody() = runBlocking<Unit> {
        for ((i, r) in listOf(MarkPhotoReason.CAMERA_BUSY, MarkPhotoReason.GLASSES_NOT_CONNECTED, MarkPhotoReason.GLASSES_NOT_WORN, MarkPhotoReason.NO_PHOTO_SUPPORT).withIndex()) {
            reason = r; chat(VoiceMark.Mark(null), at = t0 + i * 1_000L)
            assertEquals(r, dao.label("mk:phone:${t0 + i * 1_000L}")!!.photo)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun aRequestThatCannotBeSentLeavesTheMarkWithGlassesNotConnected() = runBlocking<Unit> {
        sendOk = false
        chat(VoiceMark.Mark(null))
        assertEquals(MarkPhotoReason.GLASSES_NOT_CONNECTED, dao.labels().single().photo)
    }

    @Test fun aRetroMarkNeverAsksForAPhoto() = runBlocking<Unit> {
        chat(VoiceMark.Retro(null))
        assertEquals(MarkPhotoReason.RETRO, dao.labels().single().photo); assertTrue(requests.isEmpty())
    }

    @Test fun withoutAPhotoPathAPhoneMarkKeepsThePhoneMarkReason() = runBlocking<Unit> {
        val plain = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 })
        plain.applyVoice(listOf(VoiceMark.Mark(null)), MarkOrigin.PHONE_CHAT, t0, plain.phonePhotoDecision())
        assertEquals(MarkPhotoReason.PHONE_MARK, dao.labels().single().photo)
    }

    @Test fun aMarkMadeDuringACallIsTaggedAndAnOrdinaryOneIsNot() = runBlocking<Unit> {
        call = true; chat(VoiceMark.Mark(null), at = t0)
        rec.onMark(MarkMessage(epoch = "e", seq = 1, atMs = 5, atElapsedMs = 5, deletionCounter = 2), MarkArrival(t0 + 5_000, true, t0 + 5_000))
        call = false; chat(VoiceMark.Mark(null), at = t0 + 10_000)
        assertTrue(dao.label("mk:phone:$t0")!!.provenance.endsWith(";call"))
        assertTrue("a glasses tap during a call is tagged too", dao.label("mk:e:1")!!.provenance.endsWith(";call"))
        assertEquals("clock:receipt", dao.label("mk:phone:${t0 + 10_000}")!!.provenance)
    }

    @Test fun undoFromTheChatRemovesTheMarkAndForgetsItsLinkedPhoto() = runBlocking<Unit> {
        chat(VoiceMark.Mark(null), at = t0)
        val meta = FrameMetaEntity("p1", "w_p1", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = t0 + 1_000, markRef = "phone:$t0")
        FrameIntake(db).commit(FrameEntity("p1", t0 + 1_000, "/p/p1.jpg", "rokid_glasses"), FrameRows(meta, FrameStateEntity("p1", "PENDING", 0, null, null, null, 1)))
        assertEquals("p1", dao.label("mk:phone:$t0")!!.frameId)
        chat(VoiceMark.Undo, at = t0 + 5_000)
        assertNull(dao.label("mk:phone:$t0")); assertEquals(listOf("p1"), forgotten)
    }

    @Test fun anUndoNeverLeavesAPhotoThatArrivesLaterLinked() = runBlocking<Unit> {
        chat(VoiceMark.Mark(null), at = t0)
        chat(VoiceMark.Undo, at = t0 + 5_000)
        assertTrue(dao.labels().isEmpty())
        assertTrue("the undo marker fences the late photo", dao.meta(FrameIntake.MARK_UNDONE_PREFIX + "mk:phone:$t0") != null)
    }
}
