package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Role
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.orchestration.nudge.NudgeEnvironment
import com.rokid.context.orchestration.nudge.NudgeHud
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** Fix round 2 (I-A, I-B): the REAL gate `AnswerService.photosAllowedNow`, with every condition driven through its real source. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskPhotoGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val noon = java.time.LocalDateTime.of(2026, 10, 12, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val noQuiet = NudgeHud(null, Verbosity.MEDIUM, ZoneOffset.UTC)

    private var net = com.rokid.context.core.net.NetSnapshot(true, com.rokid.context.core.net.Transport.WIFI, validated = true, notMetered = true, notRoaming = true)

    @Before fun setUp() {
        ctx.getSharedPreferences(com.rokid.context.ai.datasaver.DataSaverStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        com.rokid.context.ai.datasaver.DataSaverGraph.resetForTests(com.rokid.context.ai.datasaver.DataSaverGraph.forTests(ctx) { net })
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        TestAi.bindAll(TestAi.graph(ctx) { "{}" })
        AskPhotoPolicy.set(ctx, true); AppConfigStore.setPipelineEnabled(ctx, true)
        NudgeEnvironment.hud = { noQuiet }
    }

    @After fun tearDown() {
        com.rokid.context.ai.datasaver.DataSaverGraph.resetForTests(null)
        NudgeEnvironment.hud = null; AskPhotoPolicy.set(ctx, false)
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun allowed() = runBlocking { AnswerService.photosAllowedNow(ctx, noon) }

    @Test fun allConditionsMetAllowsPhotos() { assertTrue(allowed()) }

    @Test fun roamingDataSaverBlocksAndCountsAndWifiAllows() {
        com.rokid.context.ai.datasaver.DataSaverGraph.get(ctx).settingsStore.setMode(com.rokid.context.core.net.DataMode.WIFI_ONLY)
        net = net.copy(transport = com.rokid.context.core.net.Transport.CELLULAR, notMetered = false, notRoaming = false)
        assertFalse(allowed()); assertFalse(allowed())
        assertEquals(2, com.rokid.context.ai.datasaver.DataSaverGraph.get(ctx).ledger.today().dropped)
        net = net.copy(transport = com.rokid.context.core.net.Transport.CELLULAR, notRoaming = true) // home mobile data: Wi-Fi only mode blocks it (the default mode does not)
        assertFalse(allowed())
        net = com.rokid.context.core.net.NetSnapshot.UNKNOWN
        assertFalse("an unknown network is not allowed", allowed())
        net = com.rokid.context.core.net.NetSnapshot(true, com.rokid.context.core.net.Transport.WIFI, validated = true, notMetered = true, notRoaming = true)
        assertTrue(allowed())
    }

    @Test fun switchOffBlocks() { AskPhotoPolicy.set(ctx, false); assertFalse(allowed()) }

    @Test fun unknownHudCountsAsQuiet() { NudgeEnvironment.hud = null; assertFalse(allowed()) }

    @Test fun insideQuietHoursBlocksAndOutsideDoesNot() {
        NudgeEnvironment.hud = { NudgeHud(QuietHours(11 * 60, 13 * 60), Verbosity.MEDIUM, ZoneOffset.UTC) }
        assertFalse(allowed())
        NudgeEnvironment.hud = { NudgeHud(QuietHours(22 * 60, 7 * 60), Verbosity.MEDIUM, ZoneOffset.UTC) }
        assertTrue(allowed())
    }

    @Test fun noQuietHoursConfiguredIsNotQuiet() { NudgeEnvironment.hud = { noQuiet }; assertTrue(allowed()) }

    @Test fun answerEndpointWithoutVisionBlocks() = runBlocking {
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) // a text-only Answer model (CHAT, no VISION)
        TestAi.bindAll(TestAi.graph(ctx) { "{}" }, setOf(Capability.CHAT))
        assertFalse(AnswerService.photosAllowedNow(ctx, noon))
    }

    @Test fun visionOnTheAnswerEndpointAllowsAndOnlyAnswerCounts() = runBlocking {
        assertTrue(AnswerService.photosAllowedNow(ctx, noon))
        val ai = AiGraph.get(ctx)
        ai.bindings.clear(Role.ANSWER) // no ANSWER binding at all: no vision to rely on
        assertFalse(AnswerService.photosAllowedNow(ctx, noon))
    }

    @Test fun deletedAnswerEndpointBlocks() = runBlocking {
        AiGraph.get(ctx).endpoints.delete("az") // the binding still points at it
        assertFalse(AnswerService.photosAllowedNow(ctx, noon))
    }

    /** I-A: the ⏸ button writes pause records, not the legacy flag. */
    @Test fun anActivePauseBlocksAndAnEndedOneDoesNot() = runBlocking {
        val dao = MemoryGraph.get(ctx).db.pauseDao()
        dao.insertInterval(PauseIntervalEntity("p1", "PHONE", noon - 60_000, null))
        assertFalse(AnswerService.photosAllowedNow(ctx, noon))
        dao.closeInterval("p1", noon - 1)
        assertTrue(AnswerService.photosAllowedNow(ctx, noon))
    }

    @Test fun theLegacyPipelineFlagStillBlocks() = runBlocking {
        AppConfigStore.setPipelineEnabled(ctx, false)
        assertFalse(AnswerService.photosAllowedNow(ctx, noon))
    }

    @Test fun theImageEndpointIsTheAnswerPrimary() = runBlocking {
        assertEquals("az", AnswerService.answerImageEndpoint(ctx))
    }
}
