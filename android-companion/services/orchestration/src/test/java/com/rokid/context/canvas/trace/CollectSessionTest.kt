package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DeletionEpochStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CollectSessionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences(CollectSession.PREFS, Context.MODE_PRIVATE)
    private val epoch = DeletionEpochStore.of(ctx)
    private var now = 1_000_000L
    private fun session() = CollectSession(prefs, epoch) { now }
    @Before @After fun clean() {
        prefs.edit().clear().commit(); ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit(); now = 1_000_000L
    }

    @Test fun controlCarriesPersistedEpoch() {
        val s = session()
        val c1 = s.start()
        val e1 = epoch.read()
        assertEquals(e1.installId, c1.installId); assertEquals(e1.counter, c1.deletionCounter); assertTrue(c1.installId.isNotBlank())
        epoch.commitWipe(now) // a Delete all
        val c2 = s.refresh()
        assertEquals("the new counter, read from the persisted epoch, not a cached one", e1.counter + 1, c2.deletionCounter)
        assertEquals(e1.installId, c2.installId)
        assertEquals(epoch.read().counter, session().control().deletionCounter) // a fresh instance (a restarted process) agrees
    }

    @Test fun sendIsAlwaysRequestedAndTheSessionIsBounded() {
        val s = session()
        assertTrue("send even when no session runs", s.control().send); assertFalse(s.control().collect)
        val c = s.start(20 * 60_000L)
        assertTrue(c.collect); assertTrue(c.send); assertEquals(now + 20 * 60_000L, c.collectUntilMs); assertEquals(now, c.sessionStartMs); assertEquals(now, c.atMs)
        assertEquals("a huge request is capped", now + CollectSession.MAX_DURATION_MS, s.start(Long.MAX_VALUE / 4).collectUntilMs)
        assertEquals("a tiny one is raised", now + CollectSession.MIN_DURATION_MS, s.start(1).collectUntilMs)
    }

    @Test fun theSessionEndsOnItsOwnAndSurvivesARestart() {
        session().start(60_000L)
        now += 30_000
        assertTrue("a new instance (process restart) still collects", session().control().collect)
        assertTrue(session().active())
        now += 30_001
        val c = session().control()
        assertFalse(c.collect); assertNull(c.collectUntilMs); assertFalse(session().active()); assertTrue(c.send)
    }

    @Test fun stopEndsItAndFlowFollows() {
        val s = session()
        s.start()
        assertTrue(s.flow.value.collect)
        s.stop()
        assertFalse(s.flow.value.collect); assertFalse(session().active())
    }

    @Test fun tombstonesAndAForgetRangeRideOneMessage() {
        val c = session().control(listOf("trace-aaaa-0001"), 10, 20)
        assertEquals(listOf("trace-aaaa-0001"), c.tombstones); assertEquals(10L, c.forgetFromMs); assertEquals(20L, c.forgetToMs)
        assertTrue(session().control().tombstones.isEmpty())
    }
}
