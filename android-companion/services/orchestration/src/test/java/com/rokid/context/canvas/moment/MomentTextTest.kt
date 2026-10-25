package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A Moment carries TEXT: its timestamp plus the visible memory text of its photos (v0.9.6). Only valid, visible memories count. The transcript is its own object and stays null. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentTextTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val resolver = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    private val id = MomentId.format(SEC)
    private val stamp = java.time.Instant.ofEpochMilli(MS).toString() // 2026-..Z, whole seconds
    @After fun close() = db.close()

    private suspend fun stored() = db.momentDao().moment(id)!!

    @Test fun textIsTheTimestampThenTheVisibleMemoryTextsOfItsPhotosInOrder() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 700)
        fx.memory("a1", "b", createdAt = 20, text = "second fact"); fx.memory("z1", "a", createdAt = 10, text = "first fact")
        builder.build(listOf(id))
        assertEquals("$stamp\nfirst fact\nsecond fact", stored().text)
        assertEquals("the resolver says the same", stored().text, resolver.resolve(listOf(id)).single().text)
    }

    @Test fun aHiddenAndAForgottenMemoryAreNotInTheText() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        fx.memory("ok", "a", text = "visible fact", createdAt = 1)
        fx.memory("hid", "a", text = "hidden fact", state = ReviewState.NEEDS_REVIEW, createdAt = 2)
        fx.memory("gone", "a", text = "forgotten fact", state = ReviewState.INVALIDATED, createdAt = 3)
        builder.build(listOf(id))
        assertEquals("$stamp\nvisible fact", stored().text)
    }

    @Test fun aDerivedMemoryWhoseSourcesAreAllForgottenIsStaleAndNotInTheText() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        fx.memory("ok", "a", text = "visible fact", createdAt = 1)
        fx.memory("src", "a", text = "source fact", state = ReviewState.INVALIDATED, createdAt = 4)
        fx.memory("stale", "a", derived = true, text = "stale summary", createdAt = 5)
        db.forgetDao().insertEdges(listOf(MemorySourceEntity("stale", "src")))
        builder.build(listOf(id))
        assertEquals("$stamp\nvisible fact", stored().text)
    }

    @Test fun aDerivedMemoryWithALiveSourceIsVisible() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        fx.memory("src", "a", text = "source fact", createdAt = 1); fx.memory("sum", "a", derived = true, text = "good summary", createdAt = 2)
        db.forgetDao().insertEdges(listOf(MemorySourceEntity("sum", "src")))
        builder.build(listOf(id))
        assertEquals("$stamp\nsource fact\ngood summary", stored().text)
    }

    @Test fun noVisibleMemoryMeansNoTextAndAMarkMomentHasNone() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("hid", "a", state = ReviewState.NEEDS_REVIEW)
        fx.mark("L", MS + 3500)
        builder.build(listOf(id, MomentId.format(SEC + 3)))
        assertNull(stored().text)
        assertEquals(MomentType.MARK, db.momentDao().moment(MomentId.format(SEC + 3))!!.momentType)
        assertNull(db.momentDao().moment(MomentId.format(SEC + 3))!!.text)
    }

    @Test fun theTranscriptIsItsOwnObjectAndStaysNullWhateverTheTextIs() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m", "a")
        builder.build(listOf(id))
        assertNull(stored().transcript)
    }

    @Test fun aRebuildFollowsTheSourcesWhenAFactIsForgottenOrChanged() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a", text = "one", createdAt = 1); fx.memory("m2", "a", text = "two", createdAt = 2)
        builder.build(listOf(id)); assertEquals("$stamp\none\ntwo", stored().text)
        db.openHelper.writableDatabase.execSQL("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id = 'm2'")
        builder.build(listOf(id)); assertEquals("$stamp\none", stored().text)
        db.openHelper.writableDatabase.execSQL("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id = 'm1'")
        builder.build(listOf(id)); assertNull("every source forgotten: no text", stored().text)
    }

    /** Since slice C the reason is DERIVED from the marks' photo state at every build (the glasses' report or the old-glasses default), so a rebuild follows the sources; a photo clears it. */
    @Test fun aRebuildDerivesTheErrorReasonOfAFallbackMarkMomentFromItsMarkAndAPhotoClearsIt() = runBlocking<Unit> {
        fx.mark("L", MS + 500, photo = "camera_busy")
        builder.build(listOf(id))
        assertEquals("camera_busy", stored().errorReason)
        db.momentDao().replace(stored().copy(errorReason = "stale"), db.momentDao().parts(id)) // a stored value is never trusted over the sources
        builder.build(listOf(id))
        assertEquals("camera_busy", stored().errorReason)
        fx.frame("a", MS + 5); builder.build(listOf(id))
        assertEquals(MomentType.FRAME, stored().momentType); assertNull("a photo Moment has no error reason", stored().errorReason)
    }
}
