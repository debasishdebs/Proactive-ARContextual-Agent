package com.rokid.context.memory.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeferredOpenHelperFactoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theModeIsDecidedOnFirstUseNotAtBuild() = runBlocking {
        var decided = 0
        val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, "deferred_test.db")
            .openHelperFactory(DeferredOpenHelperFactory({ decided++; false }, { error("plaintext never needs a key") })).build()
        try {
            assertEquals(0, decided)
            MemoryRepository(db.memoryDao()).add(MemoryEntity("m1", null, "milk", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
            assertEquals(1, db.memoryDao().count()); assertEquals(1, decided)
        } finally { db.close(); ctx.deleteDatabase("deferred_test.db") }
    }

    @Test fun anOpenOnTheMainThreadIsRefusedBeforeTheModeOrKeyIsRead() {
        var keyReads = 0
        var decisions = 0
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx).name("guard_test.db").callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build()
        val helper = DeferredOpenHelperFactory({ decisions++; true }, { keyReads++; ByteArray(32) }, onMainThread = { true }).create(config)
        val e = runCatching { helper.writableDatabase }.exceptionOrNull()
        assertTrue(e is IllegalStateException); assertEquals("Database opened on the main thread", e!!.message)
        assertEquals(0, keyReads)
        assertEquals("the mode decision (which may migrate and read the key) must not run on main (preflight F12)", 0, decisions)
    }
}
