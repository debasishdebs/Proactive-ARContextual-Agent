package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G19 (MVP section 23): config export, every secret excluded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConfigExportTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val vault = InMemoryKeyVault()
    private lateinit var ai: AiGraph

    private companion object {
        const val FAKE_KEY = "sk-fake-EXPORT-9f3a1c77"
        const val OTHER_KEY = "fake-orphan-key-55b2"
        const val URL_SECRET = "urlpass-31d"
        const val QUERY_SECRET = "querykey-8e0"
    }

    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), vault, InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String = "{}" }
        })
        ConfigExport.PREFS_FILES.forEach { prefs(it).edit().clear().commit() }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun saveEndpoint() = runBlocking {
        ai.endpoints.save(
            Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://me:$URL_SECRET@az.example/openai?api-key=$QUERY_SECRET#frag", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT), 1),
            apiKey = FAKE_KEY,
        ).getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt", temperature = 0.3)).getOrThrow()
    }

    @Test fun exportExcludesEveryKeyField() = runBlocking {
        saveEndpoint()
        vault.put("endpoint:orphan", OTHER_KEY)
        // A secret-looking key that ever lands in a restorable file is dropped by name.
        prefs("hub_settings").edit().putString("api_key", FAKE_KEY).putString("auth_token", OTHER_KEY).commit()
        prefs("rokid_link").edit().putString("token", "fake-pairing-token-77").commit()
        val text = ConfigExport.write(ctx, ai, nowMs = 5).readText()
        assertTrue("the fake key reached the vault", vault.refs().isNotEmpty())
        for (ref in vault.refs()) assertFalse("vault value for $ref", vault.get(ref)!! in text)
        for (s in listOf(FAKE_KEY, OTHER_KEY, URL_SECRET, QUERY_SECRET, "fake-pairing-token-77", "api_key", "auth_token")) assertFalse(s, s in text)
        val endpoint = Json.parseToJsonElement(text).jsonObject["endpoints"]!!.jsonArray.single().jsonObject
        assertEquals("https://az.example/openai", endpoint["baseUrl"]!!.jsonPrimitive.content)
        assertFalse("no key reference either", "keyRef" in endpoint)
    }

    @Test fun exportIncludesSettings() = runBlocking {
        saveEndpoint()
        prefs("retrieval_settings").edit().putInt("top_k", 12).commit()
        prefs("memory_settings").edit().putInt("short_hours", 48).commit()
        prefs("voice_settings").edit().putString("language", "hi-IN").commit()
        prefs("hub_settings").edit().putString("hud_settings", "{\"v\":1,\"verbosity\":\"MAX\"}").putString("activity_sort", "A_Z").commit()
        prefs("context_sources").edit().putBoolean("source_calendar", true).commit()
        val f = ConfigExport.write(ctx, ai, nowMs = 7)
        assertEquals(MemoryExport.dir(ctx), f.parentFile)
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals(true, root["secretsExcluded"]!!.jsonPrimitive.content.toBoolean())
        val p = root["preferences"]!!.jsonObject
        assertEquals("12", p["retrieval_settings"]!!.jsonObject["top_k"]!!.jsonPrimitive.content)
        assertEquals("48", p["memory_settings"]!!.jsonObject["short_hours"]!!.jsonPrimitive.content)
        assertEquals("hi-IN", p["voice_settings"]!!.jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals("MAX", p["hub_settings"]!!.jsonObject["hud_settings"]!!.jsonObject["verbosity"]!!.jsonPrimitive.content)
        assertEquals("A_Z", p["hub_settings"]!!.jsonObject["activity_sort"]!!.jsonPrimitive.content)
        assertEquals("true", p["context_sources"]!!.jsonObject["source_calendar"]!!.jsonPrimitive.content)
        val e = root["endpoints"]!!.jsonArray.single().jsonObject
        assertEquals("Azure", e["name"]!!.jsonPrimitive.content)
        assertEquals("AZURE_OPENAI", e["kind"]!!.jsonPrimitive.content)
        val b = root["roleBindings"]!!.jsonArray.single().jsonObject
        assertEquals("ANSWER", b["role"]!!.jsonPrimitive.content)
        assertEquals("0.3", b["temperature"]!!.jsonPrimitive.content)
        assertTrue("prompts" in root)
    }
}
