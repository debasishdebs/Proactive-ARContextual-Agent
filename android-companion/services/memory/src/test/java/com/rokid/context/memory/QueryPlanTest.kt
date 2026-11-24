package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingDao
import com.rokid.context.memory.db.RetrievalDao
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B3: the hot retrieval queries seek by index/rowid and never build a temporary sort, whatever the library size. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueryPlanTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun plan(sql: String, vararg args: Any?): List<String> =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN " + sql.replace(Regex(":[A-Za-z]+"), "?"), args)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }

    @Test fun livePageSeeksByRowidWithoutASort() {
        val plan = plan(EmbeddingDao.LIVE_PAGE_SQL, "S", 0L, 0.0, 500)
        assertTrue(plan.toString(), plan.none { "TEMP B-TREE" in it })
        assertTrue(plan.toString(), plan.any { "index_memory_vector_spaceId (spaceId=? AND rowid>?)" in it })
        assertTrue(plan.toString(), plan.any { it.startsWith("SEARCH") && "USING INDEX sqlite_autoindex_memory_1 (id=?)" in it })
    }

    @Test fun lexicalCursorHasNoTempSort() {
        val plan = plan(RetrievalDao.LEXICAL_CURSOR_SQL, "milk", 0.0, Long.MIN_VALUE)
        assertTrue(plan.toString(), plan.none { "TEMP B-TREE" in it })
        assertTrue(plan.toString(), plan.any { "VIRTUAL TABLE" in it && "memory_fts" in it })
        assertTrue(plan.toString(), plan.any { it.startsWith("SEARCH") && "memory USING INTEGER PRIMARY KEY (rowid=?)" in it })
    }
}
