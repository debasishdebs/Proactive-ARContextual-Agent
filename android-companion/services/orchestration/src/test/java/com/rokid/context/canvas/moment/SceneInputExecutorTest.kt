package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The executor of `input.scenes`: stored Scenes of a window with their window, rule, activity and Moment references; a derived field only while it is fresh. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneInputExecutorTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val builder = SceneBuilder(db, enabled = { true }, clock = { 5 })
    private val audit = ObjectAudit(db.objectDao()) { 7 }
    private val exec = SceneInputExecutor(db)
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)
    private fun ref(sec: Long) = ObjectRef(ObjectKind.SCENE, sid(sec), 1)

    private suspend fun photo(sec: Long, motion: String = "STILL") {
        fx.frame("f$sec", t(sec) + 5)
        db.frameMetaDao().upsert(FrameMetaEntity("f$sec", null, null, motion, false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = t(sec) + 5))
        moments.build(listOf(mid(sec)))
    }

    private suspend fun seed() { photo(0); photo(30); photo(60); photo(400, "WALKING"); builder.rebuildWindow(t(0), t(3599)) }

    @Test fun itIsTheExecutorOfTheScenesNode() = assertEquals(NodeTypes.INPUT_SCENES, exec.type)

    @Test fun itReturnsTheScenesOfAWindowOldestFirstWithTheirFacts() = runBlocking<Unit> {
        seed()
        val all = exec.run(t(0), t(3599))
        assertEquals(listOf(sid(0), sid(400)), all.map { it.id })
        val a = all.first()
        assertEquals(listOf(mid(0), mid(30), mid(60)), a.momentIds); assertEquals("STILL", a.activity); assertEquals("ACTIVITY_CHANGE", a.rule)
        assertEquals(t(0), a.startMs); assertEquals(t(60) + 999, a.endMs); assertEquals(hour, a.hourStartMs); assertEquals(1, a.membershipRev)
        assertEquals(listOf(sid(400)), exec.run(t(300), t(3599)).map { it.id })
        assertEquals(listOf(sid(0)), exec.run(t(50), t(70)).map { it.id }) // a Scene is in the window when it overlaps it
        assertEquals(emptyList<String>(), exec.run(t(100), t(399)).map { it.id })
        assertEquals(listOf(sid(0)), exec.run(t(0), t(3599), limit = 1).map { it.id })
    }

    @Test fun aFreshDerivedFieldIsReturnedAndAStaleOneIsNot() = runBlocking<Unit> {
        seed()
        audit.writeDerived(ref(0), DerivedField.SUMMARY, "walked by the sea", null, 1); audit.writeDerived(ref(0), DerivedField.EMBEDDING, null, "emb:1", 1)
        audit.writeDerived(ref(400), DerivedField.CAPTION, "a street", null, 1); audit.markStale(ObjectKind.SCENE, sid(400))
        val all = exec.run(t(0), t(3599))
        assertEquals("walked by the sea", all[0].derived[DerivedField.SUMMARY]?.value); assertEquals("emb:1", all[0].derived[DerivedField.EMBEDDING]?.ref)
        assertTrue(all[1].derived.isEmpty())
    }

    @Test fun forgettingOnePhotoHidesTheSceneSummaryAtOnceAndNoReaderSeesIt() = runBlocking<Unit> {
        seed()
        DerivedField.ALL.forEach { audit.writeDerived(ref(0), it, "summary mentioning the cat", "emb:cat", 1) }
        assertEquals("summary mentioning the cat", exec.run(t(0), t(3599)).first().derived[DerivedField.SUMMARY]?.value)
        forget.forgetMoments(listOf(mid(30)))
        val after = exec.run(t(0), t(3599)).first { it.id == sid(0) }
        assertEquals(listOf(mid(0), mid(60)), after.momentIds)
        assertTrue("no derived field of a Scene that lost a member", after.derived.isEmpty())
        DerivedField.ALL.forEach { assertNull(audit.readDerived(ref(0), it)) }
        assertFalse(exec.toJson(exec.run(t(0), t(3599))).toString().contains("cat"))
        assertNotNull("the stale row is kept until it is rebuilt", audit.staleSince(ref(0), DerivedField.SUMMARY))
        audit.writeDerived(ref(0), DerivedField.SUMMARY, "rebuilt without the photo", null, 2) // the later rebuild makes it visible again
        assertEquals("rebuilt without the photo", exec.run(t(0), t(3599)).first { it.id == sid(0) }.derived[DerivedField.SUMMARY]?.value)
    }

    @Test fun theJsonCarriesIdsWindowRuleActivityAndMomentRefs() = runBlocking<Unit> {
        seed()
        val j = exec.toJson(exec.run(t(0), t(3599))).toString()
        listOf("\"id\":\"${sid(0)}\"", "\"rule\":\"ACTIVITY_CHANGE\"", "\"activity\":\"WALKING\"", "\"momentIds\":[\"${mid(0)}\",\"${mid(30)}\",\"${mid(60)}\"]", "\"startMs\":${t(0)}").forEach { assertTrue(it + " in " + j, j.contains(it)) }
    }
}
