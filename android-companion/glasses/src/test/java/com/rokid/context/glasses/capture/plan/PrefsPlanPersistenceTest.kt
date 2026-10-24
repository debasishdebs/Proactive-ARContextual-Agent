package com.rokid.context.glasses.capture.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.plan.PlanReceiver
import com.rokid.context.capture.plan.ReceiverStateJson
import com.rokid.context.core.model.PlanAckState
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrefsPlanPersistenceTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(PrefsPlanPersistence.PREFS, Context.MODE_PRIVATE)

    @Test fun aStagedTrialSurvivesAProcessRestartAndResumesOnTheSameBoot() {
        prefs.edit().clear().commit()
        assertNull(PrefsPlanPersistence(prefs).load())
        val first = PlanController(PrefsPlanPersistence(prefs), { 1_000L }, { "bc:4" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
        assertEquals(PlanAckState.STAGED, first.onMessage(PlanTestKit.trial(1, PlanTestKit.plan(), 60_000)).state)
        assertTrue(prefs.getString("state", "")!!.contains("\"effective\":true"))
        val second = PlanController(PrefsPlanPersistence(prefs), { 20_000L }, { "bc:4" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
        assertEquals(PlanReceiver.CAPS_EXPR, second.statusAck().caps)
        assertEquals(PlanAckState.EFFECTIVE, second.statusAck().state)
        assertEquals(PlanTestKit.plan().configHash, second.active.configHash)
    }

    @Test fun anotherBootEndsItAtLoadAndTheRevertIsPersisted() {
        prefs.edit().clear().commit()
        PlanController(PrefsPlanPersistence(prefs), { 1_000L }, { "bc:4" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
            .onMessage(PlanTestKit.trial(1, PlanTestKit.plan(), 3_600_000))
        val next = PlanController(PrefsPlanPersistence(prefs), { 50L }, { "bc:5" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
        assertEquals(ActiveConfig.SETTINGS, next.active)
        assertEquals("auto:reboot", ReceiverStateJson.decode(prefs.getString("state", null))!!.terminal[1])
    }

    @Test fun anUnreadableStoredStateIsNoPlan() {
        prefs.edit().putString("state", "{not json").commit()
        val c = PlanController(PrefsPlanPersistence(prefs), { 1L }, { "bc:1" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
        assertEquals(0L, c.statusAck().gen); assertEquals(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun aCorruptStoredStateStillRefusesAReSentRevertedGen() {
        prefs.edit().clear().commit()
        val ctx = com.rokid.context.core.model.context.ContextState.UNKNOWN
        val first = PlanController(PrefsPlanPersistence(prefs), { 1_000L }, { "bc:4" }, { ctx }, {})
        first.onMessage(PlanTestKit.trial(1, PlanTestKit.plan(), 60_000)); first.revert("auto:ceiling")
        prefs.edit().putString("state", "{truncated").commit()           // the state is lost, the high-water mark is not
        val sent = ArrayList<com.rokid.context.core.model.CapturePlanAck>()
        val c = PlanController(PrefsPlanPersistence(prefs), { 2_000L }, { "bc:4" }, { ctx }, { sent += it })
        val again = c.onMessage(PlanTestKit.trial(1, PlanTestKit.plan(), 60_000))
        assertEquals(PlanAckState.REVERTED, again.state, "a re-sent reverted gen is not applied again")
        assertEquals(ActiveConfig.SETTINGS, c.active)
        assertEquals(PlanAckState.STAGED, c.onMessage(PlanTestKit.trial(2, PlanTestKit.plan(), 60_000)).state)
    }
}
