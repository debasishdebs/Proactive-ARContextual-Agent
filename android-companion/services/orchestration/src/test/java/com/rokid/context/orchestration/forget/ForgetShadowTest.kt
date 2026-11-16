package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Forget lifecycle M2: the runtime shadow hook is OFF by default, changes nothing when ON, and logs only counts and a divergence word. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetShadowTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val lines = mutableListOf<String>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        PcLog.testOnlyResetNeverCallFromProduction()
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { synchronized(lines) { lines += line.msg } } })
    }
    @After fun tearDown() { DeveloperFlags.setForgetShadow(ctx, false); PcLog.testOnlyResetNeverCallFromProduction(); graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun world() {
        val jpg = File(ctx.filesDir, "rokid_archive/s.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f_shadow", 1, jpg.absolutePath, "rokid_glasses"))
        graph.db.memoryDao().insert(MemoryEntity("m_shadow", "f_shadow", "SECRET-SHADOW-TEXT", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "SECRET-SHADOW-TEXT"))
        graph.db.memoryDao().insert(MemoryEntity("m_derived", null, "d", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, true, "d"))
        graph.db.forgetDao().insertEdges(listOf(MemorySourceEntity("m_derived", "m_shadow")))
        graph.asks.add(AskEntity("ask_shadow", 1, "q", "a", "[\"m_shadow\"]", null, "phone", false))
    }
    private fun shadowLines() = synchronized(lines) { lines.filter { it.startsWith("shadow forget") } }

    @Test fun theFlagIsOffByDefaultAndAnOffHookLogsNothing() = runBlocking {
        assertFalse(DeveloperFlags.forgetShadow(ctx))
        world()
        assertTrue(ForgetActions.forgetMoment(ctx, "f_shadow"))
        assertTrue(shadowLines().isEmpty())
    }

    @Test fun anOnHookLogsOneMatchedLineWithCountsOnlyAndTheForgetIsUnchanged() = runBlocking {
        DeveloperFlags.setForgetShadow(ctx, true)
        world()
        assertTrue(ForgetActions.forgetMoment(ctx, "f_shadow"))
        assertTrue("the real Forget ran as always", graph.asks.byId("ask_shadow")!!.redacted && graph.db.memoryViewDao().byId("m_shadow") == null)
        assertEquals(1, shadowLines().size)
        assertTrue(shadowLines().single(), shadowLines().single().startsWith("shadow forget matched scope=PHOTO memories=1 invalidated=1 answers=1 nudges=0 nodes="))
        assertTrue("planned nodes counted: the memory, the photo, the derived memory and the ask", shadowLines().single().substringAfter("nodes=").toInt() >= 4)
        shadowLines().forEach { assertFalse("no id and no text in it", it.contains("shadow") && (it.contains("m_shadow") || it.contains("f_shadow") || it.contains("SECRET"))) }
    }

    @Test fun aFactForgetIsShadowedToo() = runBlocking {
        DeveloperFlags.setForgetShadow(ctx, true)
        world()
        assertTrue(ForgetActions.forgetFact(ctx, "m_shadow"))
        assertEquals(1, shadowLines().size); assertTrue(shadowLines().single().startsWith("shadow forget matched scope=FACT memories=1 invalidated=1 answers=1"))
    }

    @Test fun theFlagLivesInTheDeveloperPrefsFileWhichBackupAndTransferExclude() {
        assertEquals("pc_dev", DeveloperFlags.PREFS)
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { f ->
            val xml = File(root, "phone/src/main/res/xml/$f").readText()
            assertTrue("$f excludes pc_dev.xml (every section)", Regex("""path="pc_dev\.xml"""").findAll(xml).count() >= 1)
        }
    }
}
