package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Round 5: the import's Delete all generation is checked INSIDE the frame commit transaction, so no frame/meta/state row lands after a wipe. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameIntakeWipedTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()
    private fun rows(id: String) = FrameRows(
        FrameMetaEntity(id, "w_$id", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = 5_000),
        FrameStateEntity(id, "PENDING", 0, null, null, null, 1),
    )

    @Test fun aWipeSinceTheRequestBeganStoresNothing() = runBlocking<Unit> {
        val out = FrameIntake(db).commit(FrameEntity("f1", 5_000, "/p/f1.jpg", "rokid_glasses"), rows("f1")) { true }
        assertEquals(FrameIntake.Outcome.WIPED, out)
        assertTrue(db.frameDao().byIds(listOf("f1")).isEmpty())
        assertTrue(db.frameMetaDao().byWireId("w_f1") == null)
    }

    @Test fun noWipeStoresTheRowsAsBefore() = runBlocking<Unit> {
        assertEquals(FrameIntake.Outcome.ADDED, FrameIntake(db).commit(FrameEntity("f2", 5_000, "/p/f2.jpg", "rokid_glasses"), rows("f2")) { false })
        assertEquals(1, db.frameDao().byIds(listOf("f2")).size)
    }
}
