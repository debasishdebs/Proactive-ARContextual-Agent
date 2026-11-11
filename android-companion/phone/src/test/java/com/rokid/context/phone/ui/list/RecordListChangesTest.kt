package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.preview.RepoActivityData
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex r21 P1-1: an open Memories / Moments / Answers / Nudges list reads its rows again when a Forget, a retention delete or a redaction changes them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RecordListChangesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() = TestGraph.close(ctx)

    private fun source(kind: ListKind, ids: List<String>) = RecordListSource(
        kind, ids, RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) }),
        { Fixtures.ZONE }, { TODAY }, onPreview = {},
    )

    @Test fun aRetentionDeleteMakesTheOpenMemoriesListReadAgain() = runBlocking {
        val t = Fixtures.at(TODAY, 9)
        graph.db.memoryDao().insert(Fixtures.memory("m1", "Keys on the shelf", t))
        for (kind in listOf(ListKind.MEMORIES, ListKind.MOMENTS)) {
            val s = source(kind, listOf("m1"))
            assertEquals(listOf("m1"), s.load().map { it.id })
            val changed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.changes.first() } }
            kotlinx.coroutines.delay(100) // the observer is registered
            graph.db.forgetDao().deleteMemories(listOf("m1")) // what RetentionWorker does to an expired SHORT memory
            changed.await()
            assertTrue("$kind: the list read again shows nothing of it", s.load().isEmpty())
            graph.db.memoryDao().insert(Fixtures.memory("m1", "Keys on the shelf", t))
        }
    }

    @Test fun aRedactionMakesTheOpenAnswersAndNudgesListsReadAgain() = runBlocking {
        val t = Fixtures.at(TODAY, 9)
        for ((kind, write) in listOf<Pair<ListKind, suspend () -> Unit>>(
            ListKind.ANSWERS to { graph.db.askDao().upsert(Fixtures.ask("a1", t, redacted = true, answer = "Removed")) },
            ListKind.NUDGES to { graph.db.nudgeDao().insertIgnore(Fixtures.nudge("n1", t).copy(redacted = true)); Unit },
        )) {
            val s = source(kind, emptyList())
            val changed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.changes.first() } }
            kotlinx.coroutines.delay(100)
            write()
            changed.await()
        }
    }
}
