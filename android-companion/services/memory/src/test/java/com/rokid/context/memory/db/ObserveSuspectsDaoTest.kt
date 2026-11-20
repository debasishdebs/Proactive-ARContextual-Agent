package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Review T: more suspects than the list shows. [ForgetDao.observeSuspects] caps the rows but every row carries the full total, from one snapshot. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ObserveSuspectsDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.forgetDao()
    @After fun close() = db.close()

    private val shown = 20 // SuspectDescriptions.SHOWN (phone module)

    private suspend fun seed(n: Int) {
        val before = ForgetDao.TEXT_RETRY_FIXED_MS - 1
        for (i in 1..n) {
            val id = "p%02d".format(i)
            db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
            db.memoryDao().insert(MemoryEntity("rokid_kf_${id}_c0", id, "text $id", "note", "", MemoryTier.SHORT, 0.8,
                ReviewState.OK, false, before - (n - i), false, "text $id", null, null))
        }
    }

    @Test fun moreSuspectsThanShownCapTheRowsButNotTheTotalAndDeletesReEmit() = runBlocking<Unit> {
        seed(21)
        val rows = dao.observeSuspects(shown).first()
        assertEquals(shown, rows.size)
        assertTrue("every row carries the full total", rows.all { it.total == 21 })

        val newest = rows.first()
        dao.deleteMemories(listOf(newest.id))
        val after = dao.observeSuspects(shown).first()
        assertEquals(shown, after.size) // the 21st suspect now fills the list
        assertTrue(after.all { it.total == 20 })
        assertFalse("the deleted memory's snippet is gone", after.any { it.text == newest.text })

        dao.deleteMemories(dao.observeSuspects(100).first().map { it.id })
        assertEquals(emptyList<SuspectObservedRow>(), dao.observeSuspects(shown).first())
    }
}
