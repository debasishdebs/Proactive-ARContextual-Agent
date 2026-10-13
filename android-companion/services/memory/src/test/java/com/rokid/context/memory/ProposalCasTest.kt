package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProposalCasTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)

    @After fun close() = db.close()

    private fun p(id: String, expiresAt: Long = 10_000, origin: String = "a1", state: String = ProposalState.PENDING.name) =
        ProposalEntity(id, ProposalOrigin.ASK.name, origin, "REMINDER", "{}", state, expiresAt, null, null, 1)

    @Test fun concurrentHudPhoneAndExpiryEndInOneState() = runBlocking {
        repeat(50) { run ->
            val id = "p$run"
            // The answers are still fresh (499) while expiry is due (500): all three race for the one transition out of PENDING.
            assertTrue(repo.create(p(id, expiresAt = 500), nowMs = 1))
            val results = listOf(
                async(Dispatchers.IO) { if (repo.resolve(id, Decision.APPROVE, "hud", 499) is Resolve.Won) 1 else 0 },
                async(Dispatchers.IO) { if (repo.resolve(id, Decision.DECLINE, "phone", 499) is Resolve.Won) 1 else 0 },
                async(Dispatchers.IO) { repo.expireDue(500).count { it == id } },
            ).awaitAll()
            assertEquals("run $run: $results", 1, results.sum())
            val out = db.actionDao().logFor(id).filter { it.transition.startsWith("PENDING\u2192") }
            assertEquals("run $run", 1, out.size)
        }
    }

    @Test fun resolveAfterExpiryIsLostExpired() = runBlocking {
        repo.create(p("p1", expiresAt = 100), nowMs = 1)
        assertEquals(Resolve.Lost(ProposalState.EXPIRED), repo.resolve("p1", Decision.APPROVE, "hud", 100))
        assertEquals(ProposalState.EXPIRED.name, repo.byId("p1")!!.state)
        assertEquals(Resolve.Lost(null), repo.resolve("nope", Decision.APPROVE, "hud", 1))
    }

    @Test fun resolveWinsWhileFresh() = runBlocking {
        repo.create(p("p1"), nowMs = 1)
        assertEquals(Resolve.Won(ProposalState.EXECUTING), repo.resolve("p1", Decision.APPROVE, "hud", 5))
        assertEquals(Resolve.Lost(ProposalState.EXECUTING), repo.resolve("p1", Decision.DECLINE, "phone", 6))
        assertTrue(repo.markExecuted("p1", "ref-1", 7))
        assertFalse(repo.markFailed("p1", "X", 8))
        assertEquals("ref-1", repo.byId("p1")!!.createdRef)
        assertEquals(listOf("CREATED:PENDING", "PENDING\u2192EXECUTING", "EXECUTING\u2192EXECUTED"), db.actionDao().logFor("p1").map { it.transition })
    }

    @Test fun undoOnlyFromExecuted() = runBlocking {
        repo.create(p("p1"), nowMs = 1)
        assertFalse(repo.markUndone("p1", "hud", 2))
        repo.resolve("p1", Decision.APPROVE, "hud", 3)
        repo.markExecuted("p1", "ref", 4)
        assertTrue(repo.markUndone("p1", "hud", 5))
        val row = repo.byId("p1")!!
        assertEquals(ProposalState.DECLINED.name, row.state)
        assertEquals("undo:hud", row.resolvedBy)
        assertEquals("UNDONE", db.actionDao().logFor("p1").last().transition)
    }

    @Test fun expireForOriginsChunksOver999() = runBlocking {
        val origins = (0 until 1_200).map { "o$it" }
        origins.forEach { repo.create(p("p-$it", origin = it), nowMs = 1) }
        assertEquals(1_200, repo.expireForOrigins(origins, 2))
        assertEquals(1_200, repo.inState(ProposalState.EXPIRED).size)
        assertEquals(0, repo.inState(ProposalState.PENDING).size)
    }
}
