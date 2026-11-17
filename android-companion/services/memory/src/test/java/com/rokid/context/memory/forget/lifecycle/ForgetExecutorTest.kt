package com.rokid.context.memory.forget.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.GoldenWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle M4: what the executor adds around the v1 delete set (the sets themselves are pinned by `GoldenDeleteSetTest`, flag OFF and ON). The executor revokes the leases its in-transaction re-plan reaches (and
 * only those), the flag is OFF unless set, and a fault before the commit rolls the whole v2 Forget back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetExecutorTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val w = GoldenWorld(db)
    @After fun close() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); db.close() }

    private fun lease(vararg refs: Ref) = Leases.shared.open(Leases.Kind.ASK, refs.toList(), 0)

    @Test fun theFlagIsOffByDefault() { ForgetV2.testOverride = null; ForgetV2.provider = { false }; assertFalse(ForgetV2.on) }

    @Test fun v2ForgetFactRevokesTheLeasesItsPlanReachesAndOnlyThose() = runBlocking {
        w.seed(); ForgetV2.testOverride = true
        val reached = lease(Ref(Family.MEMORY, "mD")) // a descendant of mA: reached through the plan's dependents, never named by the Forget
        val far = lease(Ref(Family.MEMORY, "mFar"))
        try {
            w.service.forgetFact("mA")
            assertTrue("the plan reaches the derived memory", reached.isRevoked)
            assertFalse("a lease on an unrelated memory survives", far.isRevoked)
        } finally { reached.close(); far.close() }
    }

    @Test fun v2ForgetPhotoAndRangeAndMarkRevokeToo() = runBlocking {
        w.seed(); ForgetV2.testOverride = true
        val mark = lease(Ref(Family.MARK, "mkNear"))
        try { com.rokid.context.memory.forget.lifecycle.ForgetExecutor.deleteMarks(db, listOf("mkNear", "mkNear:i")); assertTrue(mark.isRevoked) } finally { mark.close() }
        val photo = lease(Ref(Family.PHOTO, "fB"))
        try { w.service.forgetMoment("fB"); assertTrue(photo.isRevoked) } finally { photo.close() }
        val range = lease(Ref(Family.MEMORY, "mFar"))
        try { w.fx.forget.forgetRange(w.tFar - 10, w.tFar + 10); assertTrue(range.isRevoked) } finally { range.close() }
    }

    @Test fun flagOffLeavesTheRevocationToTheCaller() = runBlocking {
        w.seed(); ForgetV2.testOverride = false
        val l = lease(Ref(Family.MEMORY, "mA"))
        try { w.service.forgetFact("mA"); assertFalse("legacy: the gate caller revokes (LeaseRevocation), the service does not", l.isRevoked) } finally { l.close() }
    }

    @Test fun aFaultBeforeTheCommitRollsTheV2RangeForgetBack() = runBlocking {
        w.seed(); ForgetV2.testOverride = true
        val before = com.rokid.context.memory.forget.DbSnapshot.of(db)
        w.fx.forget.testFaultBeforeCommit = { throw IllegalStateException("boom") }
        try { w.fx.forget.forgetRange(w.tA, w.tB); org.junit.Assert.fail("the fault must surface") } catch (e: IllegalStateException) { assertEquals("boom", e.message) } finally { w.fx.forget.testFaultBeforeCommit = null }
        assertTrue("nothing changed", before.deltaTo(com.rokid.context.memory.forget.DbSnapshot.of(db)).render().isBlank())
    }
}
