package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.ai.resolve.ModelPrivacyStore
import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetState
import com.rokid.context.core.net.ResolveReason
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P2-7: the production resolver wiring (PlaceResolvers.create + choose) driven from a REAL privacy store and real repositories, with no injected decision lambda. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceResolversWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseFactory.inMemory(ctx)
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val store = ModelPrivacyStore(ctx.getSharedPreferences("wire_mp", Context.MODE_PRIVATE).also { it.edit().clear().commit() }, ctx.getSharedPreferences("wire_mp_init", Context.MODE_PRIVATE).also { it.edit().clear().commit() }, java.util.concurrent.Executor { it.run() })
    private var net = NetState.VALIDATED
    private val logs = mutableListOf<String>()
    private val resolver = PlaceResolvers.create(store::current, { net }, bindings, endpoints) { logs += it }
    @After fun close() = db.close()

    private fun bindVision() = runBlocking {
        endpoints.save(Endpoint("v", "v", EndpointKind.OLLAMA, "https://example.com:1", null, listOf("m"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = null).getOrThrow()
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
    }

    @Test fun neverSetIsAutoAndResolvesOnlineWithTheEndpointKindLabel() = runBlocking {
        bindVision()
        val c = PlaceResolvers.choose(resolver)
        assertEquals(Backend.ONLINE, c.backend); assertEquals(ModelPrivacy.AUTO, c.mode); assertEquals("remote:OLLAMA", c.label)
    }

    @Test fun choosingOfflineOnlyInTheStoreIsSeenByTheNextAttemptAndNeverResolvesOnline() = runBlocking {
        bindVision()
        store.set(ModelPrivacy.OFFLINE_ONLY)
        for (n in NetState.entries) {
            net = n
            val c = PlaceResolvers.choose(resolver)
            assertNull(n.name, c.backend); assertEquals(ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL, c.reason); assertTrue(c.needsOnDeviceModel)
        }
        store.set(ModelPrivacy.AUTO); net = NetState.VALIDATED
        assertEquals(Backend.ONLINE, PlaceResolvers.choose(resolver).backend)
    }

    @Test fun autoWithNoNetworkIsTheNoNetworkReasonNotTheOfflineOnlyOne() = runBlocking {
        bindVision(); net = NetState.NONE
        val c = PlaceResolvers.choose(resolver)
        assertNull(c.backend); assertEquals(ResolveReason.NO_NETWORK_NO_LOCAL_MODEL, c.reason); assertFalse(c.needsOnDeviceModel)
    }

    @Test fun aLostSettingFailsClosedThroughTheWholeWiring() = runBlocking {
        bindVision()
        store.set(ModelPrivacy.AUTO)
        // the main file is wiped but the sentinel remembers a choice: a store over the same files reads Offline only
        ctx.getSharedPreferences("wire_mp", Context.MODE_PRIVATE).edit().clear().commit()
        val lost = ModelPrivacyStore(ctx.getSharedPreferences("wire_mp", Context.MODE_PRIVATE), ctx.getSharedPreferences("wire_mp_init", Context.MODE_PRIVATE), java.util.concurrent.Executor { it.run() })
        val r = PlaceResolvers.create(lost::current, { NetState.VALIDATED }, bindings, endpoints) { logs += it }
        val c = PlaceResolvers.choose(r)
        assertNull(c.backend); assertEquals(ModelPrivacy.OFFLINE_ONLY, c.mode); assertTrue(lost.wasReset.value)
    }

    @Test fun theLogLineHasNoEndpointNameOrAddress() = runBlocking {
        bindVision(); PlaceResolvers.choose(resolver)
        assertTrue(logs.single(), logs.single().contains("label=remote:OLLAMA") && !logs.single().contains("example") && !logs.single().contains("https"))
    }
}
