package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchemaTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    @Test fun everySpecTableExists() {
        val expected = setOf(
            "frame", "memory", "memory_fts", "memory_source", "embedding_space", "memory_vector", "forget_tombstone",
            "endpoint", "role_binding", "prompt", "prompt_version", "ask", "nudge", "proposal", "action_log",
            "pause_record", "pause_interval", "shopping_item", "note", "summary_trace",
        )
        val c = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        val tables = buildSet { while (c.moveToNext()) add(c.getString(0)) }
        c.close()
        assertEquals(emptySet<String>(), expected - tables)
    }

    @Test fun endpointAndBindingRoundTrip() = runTest {
        val e = EndpointEntity("e1", "Azure", "AZURE_OPENAI", "https://az.example", "2024-10-21", "gpt-5.4", "key:e1", "CHAT,VISION", 1)
        db.endpointDao().upsert(e)
        assertEquals(e, db.endpointDao().byId("e1"))
        db.roleBindingDao().upsert(RoleBindingEntity("ANSWER", "e1", "gpt-5.4", 0.2, 500, null, null))
        assertEquals("e1", db.roleBindingDao().byRole("ANSWER")!!.endpointId)
        assertEquals(1, db.endpointDao().all().size)
    }
}
