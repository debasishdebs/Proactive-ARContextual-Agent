package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryExportTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun exportHasMemoriesAndAsksInTheCacheExportsFolder() = runBlocking {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "दूध फ्रिज में है"))
        g.asks.add(AskEntity("a1", 2, "milk?", "In the fridge", "[\"m1\"]", null, "phone_typed", false))
        val f = MemoryExport.write(ctx, nowMs = 1_760_000_000_000)
        assertEquals("exports", f.parentFile!!.name); assertTrue(f.parentFile!!.parentFile == ctx.cacheDir)
        val json = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals("दूध फ्रिज में है", json["memories"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("In the fridge", json["asks"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        assertFalse(f.readText().contains("apiKey") || f.readText().contains("endpoint"))
    }

    /** F22: a new export replaces older memory exports only; other files in cache/exports (diagnostics, Task 21) stay. */
    @Test fun aNewExportRemovesOnlyOlderMemoryExports() = runBlocking<Unit> {
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val diagnostics = java.io.File(dir, "proactive-context-diagnostics-1.txt").apply { writeText("d") }
        val old = MemoryExport.write(ctx, nowMs = 1)
        val new = MemoryExport.write(ctx, nowMs = 2)
        assertFalse(old.exists()); assertTrue(new.exists()); assertTrue(diagnostics.exists())
        dir.deleteRecursively()
    }
}
