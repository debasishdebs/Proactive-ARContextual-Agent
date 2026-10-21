package com.rokid.context.phone.ui.settings.diagnostics

import android.util.Log
import com.rokid.context.ai.local.NomicText
import com.rokid.context.ai.local.RecallAtK
import com.rokid.context.memory.ImageDecodeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Debug-only decision test for the stored image width (spec §F.5, Ruling 17): embeds the user's own photos at 768 and at the
 * Matryoshka-truncated 256 and compares recall@3 against labels the user wrote. Everything is in-memory scratch (nothing touches
 * the live vector space) and bounded, cancellable and gated. Logs and the result file carry counts and numbers only, never query
 * text, frame ids or photo content.
 *
 * Input: `<dir>/queries.json` = `[{"query": "...", "frameIds": ["..."], "small": true}]`. Output: `<dir>/result.json`.
 */
class PhotoDimEval(
    private val dir: File,
    private val photos: PhotoLoader,
    private val embedder: Embedder,
    /** True while bulk work may run (thermal and battery, as for backfill); checked before every batch. */
    private val gate: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Raw model outputs for the bundle: 768 CLS per JPEG, 768 mean-pooled per already-prefixed text. Undecodable image -> [ImageDecodeException]; anything else is retryable. */
    interface Embedder {
        suspend fun images(jpegs: List<ByteArray>): List<FloatArray>
        suspend fun queries(prefixed: List<String>): List<FloatArray>
    }

    /** JPEG bytes by frame id; ids that can't be read (gone, tombstoned, unreadable) are simply absent. Photos never leave the phone. */
    fun interface PhotoLoader { suspend fun load(ids: List<String>): Map<String, ByteArray> }

    data class Progress(val done: Int, val total: Int)

    sealed interface Outcome {
        /** The fixture is too small; the message carries counts only. */
        data class Refused(val message: String) : Outcome
        /** Heat or battery gate closed; nothing was written. */
        data class Blocked(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
        data class Done(val r768: RecallAtK.Result, val r256: RecallAtK.Result, val pass256: Boolean) : Outcome {
            val verdict: String get() = if (pass256) VERDICT_PASS else VERDICT_KEEP
        }
    }

    suspend fun run(onProgress: (Progress) -> Unit = {}): Outcome = withContext(dispatcher) {
        val fixture = try { parse(File(dir, QUERIES_FILE).readText()) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            return@withContext Outcome.Failed("Couldn't read $QUERIES_FILE (${e.javaClass.simpleName})")
        }
        refusal(fixture.photoIds.size, fixture.queries)?.let { return@withContext Outcome.Refused(it) }
        val done = withTimeoutOrNull(TIME_LIMIT_MS) { runBounded(fixture, onProgress) }
        done ?: Outcome.Failed("Stopped: the test took longer than ${TIME_LIMIT_MS / 60_000} minutes")
    }

    private suspend fun runBounded(f: Fixture, onProgress: (Progress) -> Unit): Outcome {
        try {
            val ids = f.photoIds.toList()
            val total = ids.size + f.queries.size
            var processed = 0
            val images = LinkedHashMap<String, FloatArray>()
            for (chunk in ids.chunked(BATCH)) {
                gateOrBlock()?.let { return it }
                currentCoroutineContext().ensureActive()
                val bytes = photos.load(chunk)
                val present = chunk.filter { bytes[it] != null }
                embedImages(present, bytes).forEach { (id, v) -> images[id] = v }
                processed += chunk.size; onProgress(Progress(processed, total))
            }
            refusal(images.size, f.queries)?.let { return Outcome.Refused(it) }
            val vectors = ArrayList<Pair<RecallAtK.Query, FloatArray>>(f.queries.size)
            for (chunk in f.queries.chunked(BATCH)) {
                gateOrBlock()?.let { return it }
                currentCoroutineContext().ensureActive()
                val raw = embedder.queries(chunk.map { NomicText.QUERY + it.text })
                check(raw.size == chunk.size) { "unexpected text output" }
                chunk.forEachIndexed { i, q -> vectors += q to raw[i] }
                processed += chunk.size; onProgress(Progress(processed, total))
            }
            val r768 = RecallAtK.evaluate(images, vectors, 768)
            val r256 = RecallAtK.evaluate(images, vectors, 256)
            val pass = RecallAtK.choose256(r768, r256)
            writeResult(r768, r256, pass)
            Log.i(TAG, "done photos=${images.size} queries=${r768.queries} small=${r768.smallQueries} verdict=${if (pass) VERDICT_PASS else VERDICT_KEEP}")
            return Outcome.Done(r768, r256, pass)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "failed class=${e.javaClass.simpleName}")
            return Outcome.Failed("The test failed (${e.javaClass.simpleName}) — try again")
        }
    }

    private fun gateOrBlock(): Outcome.Blocked? = if (gate()) null else Outcome.Blocked(BLOCKED)

    /** A batch with an undecodable photo is retried one by one so only that photo is left out; other errors are retryable and propagate. */
    private suspend fun embedImages(ids: List<String>, bytes: Map<String, ByteArray>): List<Pair<String, FloatArray>> {
        if (ids.isEmpty()) return emptyList()
        return try {
            val out = embedder.images(ids.map { bytes.getValue(it) })
            check(out.size == ids.size) { "unexpected image output" }
            ids.zip(out)
        } catch (_: ImageDecodeException) {
            ids.mapNotNull { id ->
                try { id to embedder.images(listOf(bytes.getValue(id))).single() } catch (_: ImageDecodeException) { null }
            }
        }
    }

    private fun writeResult(r768: RecallAtK.Result, r256: RecallAtK.Result, pass: Boolean) {
        dir.mkdirs()
        val json = JSONObject()
            .put("version", 1)
            .put("verdict", if (pass) VERDICT_PASS else VERDICT_KEEP)
            .put("r768", numbers(r768)).put("r256", numbers(r256))
            .put("overallDropPoints", (r768.overall - r256.overall) * 100.0)
            .put("smallDropPoints", (r768.small - r256.small) * 100.0)
        val tmp = File(dir, "$RESULT_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, RESULT_FILE))) { File(dir, RESULT_FILE).writeText(json.toString()); tmp.delete() }
    }

    private fun numbers(r: RecallAtK.Result) = JSONObject()
        .put("dims", r.dims).put("overall", r.overall).put("small", r.small)
        .put("queries", r.queries).put("smallQueries", r.smallQueries).put("photos", r.photos)

    class Fixture(val queries: List<RecallAtK.Query>, val photoIds: Set<String>)

    companion object {
        private const val TAG = "PhotoDimEval"
        const val QUERIES_FILE = "queries.json"
        const val RESULT_FILE = "result.json"
        const val VERDICT_PASS = "PASS_256"
        const val VERDICT_KEEP = "KEEP_768"
        const val MIN_PHOTOS = 60
        const val MIN_QUERIES = 40
        const val MIN_SMALL = 15
        const val MAX_PHOTOS = 400
        const val MAX_QUERIES = 400
        const val BATCH = 8
        const val TIME_LIMIT_MS = 20 * 60_000L
        const val BLOCKED = "Paused: the phone is warm, or the battery is under 70% and not charging. Plug in or let it cool, then run again."

        /** The Diagnostics row exists only in debug builds with Developer mode on. */
        fun rowVisible(debug: Boolean, developerMode: Boolean): Boolean = debug && developerMode

        /** Counts only; the caps keep a mistaken fixture from running for hours. */
        fun parse(json: String): Fixture {
            val a = JSONArray(json)
            val queries = ArrayList<RecallAtK.Query>(a.length())
            val ids = LinkedHashSet<String>()
            for (i in 0 until minOf(a.length(), MAX_QUERIES)) {
                val o = a.getJSONObject(i)
                val frames = o.getJSONArray("frameIds").let { f -> (0 until f.length()).map { f.getString(it) }.filter { it.isNotBlank() } }
                if (frames.isEmpty() || o.getString("query").isBlank()) continue
                ids += frames
                queries += RecallAtK.Query(o.getString("query"), frames.toSet(), o.optBoolean("small", false))
            }
            return Fixture(queries, ids.take(MAX_PHOTOS).toSet())
        }

        fun refusal(photos: Int, queries: List<RecallAtK.Query>): String? {
            val small = queries.count { it.small }
            if (photos >= MIN_PHOTOS && queries.size >= MIN_QUERIES && small >= MIN_SMALL) return null
            return "Need at least $MIN_PHOTOS photos, $MIN_QUERIES queries and $MIN_SMALL small-object queries; found $photos photos, ${queries.size} queries, $small small."
        }

        /** On-screen summary lines: numbers only. */
        fun lines(d: Outcome.Done): List<String> = listOf(
            line(d.r768), line(d.r256),
            "Drop at 256: overall ${fmt((d.r768.overall - d.r256.overall) * 100)} points (limit 3), small ${fmt((d.r768.small - d.r256.small) * 100)} points (limit 5)",
            if (d.pass256) "PASS 256 — store images at 256" else "KEEP 768 — leave the image width at 768",
        )

        private fun line(r: RecallAtK.Result) =
            "${r.dims}: recall@3 ${fmt(r.overall * 100)}% overall · ${fmt(r.small * 100)}% small (${r.queries} queries, ${r.smallQueries} small, ${r.photos} photos)"

        private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    }
}
