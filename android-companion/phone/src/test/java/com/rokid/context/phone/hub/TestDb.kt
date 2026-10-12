package com.rokid.context.phone.hub

import android.content.Context
import androidx.room.Room
import com.rokid.context.memory.db.ContextDatabase

/** In-memory Room that runs queries on the calling thread, so hub coroutines stay on the test scheduler. */
fun testDb(context: Context): ContextDatabase =
    Room.inMemoryDatabaseBuilder(context, ContextDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
