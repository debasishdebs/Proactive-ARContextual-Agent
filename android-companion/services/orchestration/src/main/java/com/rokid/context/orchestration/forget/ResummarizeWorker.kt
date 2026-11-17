package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SummaryOutput
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.core.model.TextHygiene
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SearchNormalizer
import com.rokid.context.orchestration.EmbeddingWorker
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.RetryClassifier
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * After a Forget (spec §6.1.1, R-77): rebuilds every INVALIDATED derived memory from the sources that survive, into a
 * new id, so forgotten text is never kept and never reaches a model.
 *
 * Topological order (Kahn) over the invalidated set, so a parent is rebuilt (and its child edges re-pointed) before its
 * child. Members of a leftover cycle are treated as having no valid sources. Sources = parents that exist and are not
 * INVALIDATED. None → the memory is deleted with its edges and vectors. Otherwise SUMMARIZE over the source texts
 * gives `rokid_mem_rs_<uuid>` (derived, same tier, OK); edges new→sources are added, child edges old→new re-pointed and
 * the old row deleted, in one transaction that re-checks the sources and the Delete-all generation.
 * A failed memory is skipped (it stays hidden) and the rest continue; the run then retries per RetryClassifier
 * (15 min backoff, at most [ResummarizeWorker.MAX_ATTEMPTS] runs). Rebuilt rows keep the original `createdAt`. Runs while capture is paused (stored data only).
 */
class ResummarizeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val outcome = try {
            val ai = AiGraph.get(applicationContext)
            val r = Resummarizer(MemoryGraph.get(applicationContext), ai.runner).run()
            if (r.changed) EmbeddingWorker.enqueue(applicationContext)
            r.failure
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e
        } ?: return Result.success()
        // Fix round 1 I8: RetryClassifier decides; at most MAX_ATTEMPTS runs. The failed memories stay hidden.
        val kind = RetryClassifier.classify(outcome)
        if (kind == RetryClassifier.Outcome.CANCELLED) throw outcome
        PcLog.w(TAG, "re-summarise ${kind.name} attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS: ${SafeLog.describe(outcome)}")
        return if (kind == RetryClassifier.Outcome.RETRY && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "ResummarizeWorker"
        const val NAME = "resummarize"
        const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<ResummarizeWorker>().setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES).build()
            // APPEND_OR_REPLACE: a Forget during a run chains one more run, so its new invalidations are never missed.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** The worker's logic over the data graph and a model runner. Throws on a model failure (nothing written for that memory). */
internal class Resummarizer(private val graph: MemoryGraph, private val runner: RoleRunner) {
    private val db = graph.db
    private val dao = db.forgetDao()

    /** [failure]: the first per-memory failure (that memory was skipped, the others still ran); null = none. */
    class Outcome(val changed: Boolean, val failure: Throwable?)

    suspend fun run(): Outcome {
        var changed = false
        var failure: Throwable? = null
        val skipped = mutableSetOf<String>()
        while (true) {
            val batch = dao.invalidatedDerived(BATCH + skipped.size).filter { it.id !in skipped }.take(BATCH)
            if (batch.isEmpty()) return Outcome(changed, failure)
            val generation = MemoryWipe.generation.get()
            var progressed = false
            for ((m, inCycle) in order(batch)) {
                try {
                    if (process(m, inCycle, generation)) progressed = true else skipped += m.id
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // Fix round 1 I8: skip this memory (it stays hidden) and continue with the others.
                    skipped += m.id
                    if (failure == null) failure = e
                }
            }
            changed = changed || progressed
            // A lost fence (Delete all, a source forgotten meanwhile) leaves rows for the next run, never a spin.
            if (!progressed || batch.size < BATCH) return Outcome(changed, failure)
        }
    }

    /** Kahn's order over [set]; each entry is (memory, inCycle). */
    private suspend fun order(set: List<MemoryEntity>): List<Pair<MemoryEntity, Boolean>> {
        val ids = set.map { it.id }.toSet()
        val parents = ids.chunked(CHUNK).flatMap { dao.edgesFrom(it) }.groupBy({ it.memoryId }, { it.sourceMemoryId })
        val indeg = ids.associateWith { id -> parents[id].orEmpty().count { it in ids } }.toMutableMap()
        val children = parents.flatMap { (child, ps) -> ps.filter { it in ids }.map { it to child } }.groupBy({ it.first }, { it.second })
        val byId = set.associateBy { it.id }
        val queue = ArrayDeque(set.filter { indeg[it.id] == 0 }.map { it.id })
        val out = mutableListOf<Pair<MemoryEntity, Boolean>>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            out += byId.getValue(id) to false
            children[id].orEmpty().forEach { c -> indeg[c] = indeg.getValue(c) - 1; if (indeg[c] == 0) queue.addLast(c) }
        }
        val done = out.map { it.first.id }.toSet()
        return out + set.filter { it.id !in done }.map { it to true }
    }

    private suspend fun process(old: MemoryEntity, inCycle: Boolean, generation: Long): Boolean {
        val sources = if (inCycle) emptyList() else liveSources(old.id)
        if (sources.isEmpty()) return db.withTransaction {
            if (com.rokid.context.provenance.ProvenanceGate(db).wiped(generation)) return@withTransaction false
            drop(old.id)
            true
        }
        // Forget lifecycle M3: a read lease over the surviving sources; a Forget that reaches one while the model runs revokes it and the rebuilt summary is not written (the row stays for the next run)
        val lease = com.rokid.context.orchestration.DataOpGate.shared.lease(com.rokid.context.memory.forget.lifecycle.Leases.Kind.RESUMMARIZE, com.rokid.context.memory.forget.lifecycle.Leases.memories(sources.map { it.id }), generation)
        try {
            val vars = PromptVars(
                recentContext = PromptComposer.dataBlock("memories", sources.map { it.text }) + "\n" +
                    PromptComposer.dataBlock("critic_notes", listOf(NOTE)),
            )
            // codex r12: the surviving source memories' text is re-checked live by the router inside the permit (a Forget meanwhile drops the call unsent)
            val out = runner.run(RoleCall(PromptKey.SUMMARIZE, vars, callClass = com.rokid.context.model.CallClass.BACKFILL,
                sources = com.rokid.context.model.PayloadSources.stored(emptyList(), sources.map { it.id }), liveness = com.rokid.context.work.frames.FrameLiveness.forGraph(graph, generation))).output as SummaryOutput
            val text = out.summary.replace(TextHygiene.SEPARATORS, " ").trim().take(260)
            if (text.isEmpty()) throw IllegalStateException("empty summary")
            val newId = "$PREFIX${UUID.randomUUID()}"
            val committed = lease.commit(db, checkLive = false) {
                if (com.rokid.context.provenance.ProvenanceGate(db).wiped(generation)) return@commit false
                // Forget fence: a source forgotten during the model call means the output may hold forgotten text.
                val now = liveSources(old.id).map { it.id }.toSet()
                if (now != sources.map { it.id }.toSet() || dao.memories(listOf(old.id)).isEmpty()) return@commit false
                // codex r17: the rebuilt summary inherits every photo its sources cite or carry (the shared rule), fenced here with the write
                val inherited = com.rokid.context.orchestration.ExtractSave.inheritedFrames(db, sources.map { it.id })
                if (com.rokid.context.provenance.ProvenanceGate(db).check(com.rokid.context.model.PayloadSources.frames(inherited), generation) != com.rokid.context.provenance.ProvenanceGate.Verdict.Live) return@commit false
                val tags = out.entities.map { it.replace(TextHygiene.SEPARATORS, " ").trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.take(5).joinToString(" ")
                val confidence = minOf(out.confidence, sources.maxOf { it.confidence }).coerceIn(0.0, 1.0)
                db.memoryDao().insert(
                    MemoryEntity(
                        id = newId, frameId = null, text = text, type = "summary", tags = tags, tier = old.tier, confidence = confidence,
                        reviewState = ReviewState.OK, trusted = false, createdAt = old.createdAt, derived = true, // fix round 1 C1: keeps its retention window
                        searchText = SearchNormalizer.normalize("$text $tags"), imageDelivered = false, // a text summary of memories: describes no photo
                    ),
                )
                dao.insertEdges(sources.map { MemorySourceEntity(newId, it.id) })
                db.frameSourceDao().insertAll(inherited.map { com.rokid.context.memory.db.FrameSourceEntity(newId, it, null, com.rokid.context.orchestration.ExtractSave.CARRIED) })
                dao.insertEdges(dao.edgesTo(listOf(old.id)).map { MemorySourceEntity(it.memoryId, newId) })
                drop(old.id)
                true
            }
            return (committed as? com.rokid.context.memory.forget.lifecycle.Leases.Commit.Committed)?.value == true

        } finally {
            lease.close()
        }
    }

    private suspend fun liveSources(id: String): List<MemoryEntity> {
        val parentIds = dao.edgesFrom(listOf(id)).map { it.sourceMemoryId }.distinct()
        return parentIds.chunked(CHUNK).flatMap { dao.memories(it) }.filter { it.reviewState != ReviewState.INVALIDATED }
    }

    private suspend fun drop(id: String) {
        val one = listOf(id)
        dao.deleteEdgesFrom(one); dao.deleteEdgesTo(one); dao.deleteVectorsFor(one); dao.deleteFrameSourcesFor(one); dao.deleteMemories(one)
    }

    companion object {
        const val PREFIX = "rokid_mem_rs_"
        const val NOTE = "re-summarise after a source was forgotten"
        private const val BATCH = 200
        private const val CHUNK = 500
    }
}
