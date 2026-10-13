package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoleBindingRepositoryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    @After fun close() = db.close()

    private suspend fun ep(id: String, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, EndpointKind.OLLAMA, "http://localhost:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()

    @Test fun pickerListsOnlyCapableEndpoints() = runTest {
        ep("chat", Capability.CHAT); ep("vision", Capability.CHAT, Capability.VISION); ep("emb", Capability.EMBEDDINGS)
        assertEquals(listOf("vision"), bindings.eligibleEndpoints(Role.VISION_EXTRACT).map { it.id })
        assertEquals(listOf("emb"), bindings.eligibleEndpoints(Role.EMBED).map { it.id })
        assertEquals(listOf("chat", "vision"), bindings.eligibleEndpoints(Role.ANSWER).map { it.id })
    }

    @Test fun refusesBindingWithoutCapability() = runTest {
        ep("chat", Capability.CHAT)
        assertTrue(bindings.set(RoleBinding(Role.VISION_EXTRACT, "chat", "m")).isFailure)
        assertTrue(bindings.set(RoleBinding(Role.ANSWER, "chat", "m")).isSuccess)
        assertEquals("chat", bindings.get(Role.ANSWER)!!.endpointId)
    }

    @Test fun usagesIncludePrimaryAndFallbackAndFlowsUpdate() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b")).getOrThrow()
        bindings.set(RoleBinding(Role.CRITIC, "b", "m")).getOrThrow()
        assertEquals(setOf(Role.ANSWER, Role.CRITIC), bindings.usages("b").toSet())
        assertEquals(listOf(Role.ANSWER), bindings.usages("a"))
        assertEquals(2, bindings.observeAll().first().size)
    }

    /** Fix round 1 (M-5): the in-use check and the delete happen together, under the lock every binding write takes. */
    @Test fun deleteEndpointIfUnusedRefusesWhileBound() = runTest {
        ep("a", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        assertEquals(listOf(Role.ANSWER), bindings.deleteEndpointIfUnused("a"))
        assertTrue(endpoints.byId("a") != null)
        bindings.clear(Role.ANSWER)
        assertEquals(emptyList<Role>(), bindings.deleteEndpointIfUnused("a"))
        assertEquals(null, endpoints.byId("a"))
    }
}
