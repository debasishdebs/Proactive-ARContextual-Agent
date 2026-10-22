package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The state machine over the real `canvas_group_state` table: the row survives a new instance (a process restart). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlanPublishGroupsDaoTest {
    @Test fun theTransitionTableIsTheRoomRowAndSurvivesARestart() = runBlocking {
        val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
        val store = DaoGroupStore(db.canvasSlice3Dao())
        val sent = ArrayList<String>(); val activated = ArrayList<String?>()
        val tx = object : PlanTransport { override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?) = SendOutcome(4, true).also { sent += publishId } }
        val saved = HashMap<Long, String>()
        val src = object : PlanSource { override suspend fun save(groupId: String, publishId: Long, planJson: String) { saved[publishId] = planJson }; override suspend fun load(groupId: String, publishId: Long) = saved[publishId] }
        fun groups() = PlanPublishGroups(store, tx, { "inst" }, { 5_000 }, { _, j -> activated += j }, src)
        groups().publish("g", 12, PlanMode.SHADOW, "{}", "h", null)
        assertEquals("SENT", db.canvasSlice3Dao().groupState("g")!!.state)
        groups().apply { recover(); onAck(CapturePlanAck(gen = 4, publishId = "12", mode = PlanMode.SHADOW, effectiveHash = "h", state = PlanAckState.EFFECTIVE, phoneInstall = "inst")) }
        assertEquals("EFFECTIVE", db.canvasSlice3Dao().groupState("g")!!.state); assertEquals(listOf<String?>("{}"), activated)
        db.close()
    }
}
