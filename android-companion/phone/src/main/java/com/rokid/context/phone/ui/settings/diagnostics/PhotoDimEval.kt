package com.rokid.context.phone.ui.settings.diagnostics

import com.rokid.context.core.log.PcLog
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
 * The corpus is the labelled photos plus up to [MAX_DISTRACTORS] other stored keyframes (seeded sample, newest first, none within
 * [EXCLUDE_MS] of a labelled frame) so the score is not a trivial ceiling. Truncating the VISION vector to 256 is not a documented
 * Nomic property (only the text model is Matryoshka-trained): this test is the only evidence there is.
 *
 * Input: `<dir>/queries.json` = `[{"query": "...", "frameIds": ["..."], "small": true}]`, built in-app by [PhotoLabels] (or pushed
 * with run-as). Output: `<dir>/result.json`.
 */
class PhotoDimEval(
    private val dir: File,
    private val photos: PhotoLoader,
    private val catalog: FrameCatalog,
    private val embedder: Embedder,
    /** True while bulk work may run (thermal and battery, as for backfill); checked before every batch. */
    private val gate: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Raw model outputs for the bundle: 768 CLS per JPEG, 768 mean-pooled per already-prefixed text. Undecodable image -> [ImageDecodeException]; anything else is retryable. */
    interface Embedder {
        suspend fun images(jpegs: List<ByteArray>): List<FloatArray>
        suspend fun queries(prefixed: List<String>): List<FloatArray>
        /** Called once when a run ends (done, refused, blocked, failed or cancelled): release private sessions. */
        fun close() {}
    }

    /** A stored keyframe that still has a photo: id and capture time. */
    data class FrameRef(val id: String, val ts: Long)

    /** Every stored keyframe whose photo exists and is not forgotten (the distractor pool and the labelled frames' times). */
    fun interface FrameCatalog { suspend fun frames(): List<FrameRef> }

    /** What a run would use right now, counts only (the screen shows it before the user starts). */
    data class Preview(val queries: Int, val small: Int, val labelledPhotos: Int, val distractors: Int)

    /** JPEG bytes by frame id; ids that can't be read (gone, tombstoned, unreadable) are simply absent. Photos never leave the phone. */
    fun interface PhotoLoader { suspend fun load(ids: List<String>): Map<String, ByteArray> }

    data class Progress(val done: Int, val total: Int)

    sealed interface Outcome {
        /** The fixture is too small; the message carries counts only. */
        data class Refused(val message: String) : Outcome
        /** Heat or battery gate closed; nothing was written. */
        data class Blocked(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
        /** [inconclusive]: 768 already scores >= 0.95 overall and on the small subset, so the corpus can't tell the widths apart: keep 768. */
        data class Done(
            val r768: RecallAtK.Result, val r256: RecallAtK.Result, val pass256: Boolean, val labelled: Int, val distractors: Int, val inconclusive: Boolean = false,
        ) : Outcome {
            val verdict: String get() = when { inconclusive -> VERDICT_INCONCLUSIVE; pass256 -> VERDICT_PASS; else -> VERDICT_KEEP }
        }
    }

    suspend fun preview(): Preview = withContext(dispatcher) {
        val f = try { parse(File(dir, QUERIES_FILE).readText()) } catch (c: CancellationException) { throw c } catch (_: Exception) { return@withContext Preview(0, 0, 0, 0) }
        val frames = catalog.frames()
        val present = f.photoIds.filter { id -> frames.any { it.id == id } }.toSet()
        val judgeable = judged(f.queries, present)
        Preview(judgeable.size, judgeable.count { it.small }, present.size, pickDistractors(frames, present).size)
    }

    suspend fun run(onProgress: (Progress) -> Unit = {}): Outcome = try {
        withContext(dispatcher) {
            val fixture = try { parse(File(dir, QUERIES_FILE).readText()) } catch (c: CancellationException) { throw c } catch (e: Exception) {
                return@withContext Outcome.Failed("Couldn't read $QUERIES_FILE (${e.javaClass.simpleName})")
            }
            val frames = try { catalog.frames() } catch (c: CancellationException) { throw c } catch (e: Exception) {
                return@withContext Outcome.Failed("The test failed (${e.javaClass.simpleName}) \u2014 try again")
            }
            val ids = frames.map { it.id }.toSet()
            val labelled = fixture.photoIds.filter { it in ids }.toSet()
            refusal(labelled.size, judged(fixture.queries, labelled))?.let { return@withContext Outcome.Refused(it) }
            val distractors = pickDistractors(frames, labelled)
            distractorRefusal(labelled.size, distractors.size)?.let { return@withContext Outcome.Refused(it) }
            val done = withTimeoutOrNull(TIME_LIMIT_MS) { runBounded(fixture, labelled, distractors.map { it.id }, onProgress) }
            done ?: Outcome.Failed("Stopped: the test took longer than ${TIME_LIMIT_MS / 60_000} minutes")
        }
    } finally {
        try { embedder.close() } catch (_: Exception) { }
    }

    private suspend fun runBounded(f: Fixture, labelled: Set<String>, distractorIds: List<String>, onProgress: (Progress) -> Unit): Outcome {
        try {
            val total = labelled.size + distractorIds.size + f.queries.size
            var processed = 0
            val images = LinkedHashMap<String, FloatArray>()
            for (chunk in (labelled.toList() + distractorIds).chunked(BATCH)) {
                gateOrBlock()?.let { return it }
                currentCoroutineContext().ensureActive()
                val bytes = photos.load(chunk)
                val present = chunk.filter { bytes[it] != null }
                embedImages(present, bytes).forEach { (id, v) -> images[id] = v }
                processed += chunk.size; onProgress(Progress(processed, total))
            }
            val loadedLabelled = images.keys.filter { it in labelled }.toSet()
            val loadedDistractors = images.size - loadedLabelled.size
            // The minimums hold on what can really be judged now (photos expire, some don't decode).
            refusal(loadedLabelled.size, judged(f.queries, loadedLabelled))?.let { return Outcome.Refused(it) }
            distractorRefusal(loadedLabelled.size, loadedDistractors)?.let { return Outcome.Refused(it) }
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
            val ceiling = r768.overall >= CEILING && r768.small >= CEILING
            val pass = !ceiling && RecallAtK.choose256(r768, r256)
            val done = Outcome.Done(r768, r256, pass, loadedLabelled.size, loadedDistractors, inconclusive = ceiling)
            writeResult(done)
            PcLog.i(TAG, "done labelled=${loadedLabelled.size} distractors=$loadedDistractors queries=${r768.queries} small=${r768.smallQueries} verdict=${done.verdict}")
            return done
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "failed class=${e.javaClass.simpleName}")
            return Outcome.Failed("The test failed (${e.javaClass.simpleName}) \u2014 try again")
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

    private fun writeResult(d: Outcome.Done) {
        dir.mkdirs()
        val json = JSONObject()
            .put("version", 2)
            .put("verdict", d.verdict)
            .put("labelledPhotos", d.labelled).put("distractors", d.distractors).put("corpusSize", d.r768.photos)
            .put("queries", d.r768.queries).put("smallQueries", d.r768.smallQueries)
            .put("hits768", d.r768.hits).put("hits256", d.r256.hits)
            .put("smallHits768", d.r768.smallHits).put("smallHits256", d.r256.smallHits)
            .put("r768", numbers(d.r768)).put("r256", numbers(d.r256))
            .put("overallDropPoints", (d.r768.overall - d.r256.overall) * 100.0)
            .put("smallDropPoints", (d.r768.small - d.r256.small) * 100.0)
        val tmp = File(dir, "$RESULT_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, RESULT_FILE))) { File(dir, RESULT_FILE).writeText(json.toString()); tmp.delete() }
    }

    private fun numbers(r: RecallAtK.Result) = JSONObject()
        .put("dims", r.dims).put("overall", r.overall).put("small", r.small)
        .put("queries", r.queries).put("smallQueries", r.smallQueries).put("photos", r.photos).put("hits", r.hits).put("smallHits", r.smallHits)

    class Fixture(val queries: List<RecallAtK.Query>, val photoIds: Set<String>)

    companion object {
        private const val TAG = "PhotoDimEval"
        const val QUERIES_FILE = "queries.json"
        const val RESULT_FILE = "result.json"
        const val VERDICT_PASS = "PASS_256"
        const val VERDICT_KEEP = "KEEP_768"
        const val VERDICT_INCONCLUSIVE = "INCONCLUSIVE"
        const val CEILING = 0.95
        const val MAX_DISTRACTORS = 1500
        const val MIN_DISTRACTORS = 300
        const val DISTRACTOR_FACTOR = 5
        const val EXCLUDE_MS = 60_000L
        const val SEED = 20261021L
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

        /** The queries that can be judged: at least one of their photos is in [present]. */
        fun judged(queries: List<RecallAtK.Query>, present: Set<String>): List<RecallAtK.Query> = queries.filter { q -> q.relevant.any { it in present } }

        /**
         * Other stored keyframes to hide the labelled ones among: none within [EXCLUDE_MS] of a labelled frame (probable unlabelled
         * true positives), a seeded random sample when there are more than [MAX_DISTRACTORS], returned newest first.
         */
        fun pickDistractors(all: List<FrameRef>, labelled: Set<String>): List<FrameRef> {
            val labelledTs = all.filter { it.id in labelled }.map { it.ts }.sorted().toLongArray()
            fun nearLabelled(ts: Long): Boolean {
                var i = java.util.Arrays.binarySearch(labelledTs, ts)
                if (i < 0) i = -i - 1
                return (i < labelledTs.size && labelledTs[i] - ts <= EXCLUDE_MS) || (i > 0 && ts - labelledTs[i - 1] <= EXCLUDE_MS)
            }
            val eligible = all.filter { it.id !in labelled && !nearLabelled(it.ts) }
                .sortedWith(compareByDescending<FrameRef> { it.ts }.thenBy { it.id })
            if (eligible.size <= MAX_DISTRACTORS) return eligible
            val picked = (eligible.indices).toMutableList().also { it.shuffle(java.util.Random(SEED)) }.take(MAX_DISTRACTORS).sorted()
            return picked.map { eligible[it] }
        }

        fun distractorRefusal(labelledPhotos: Int, distractors: Int): String? {
            val need = maxOf(MIN_DISTRACTORS, DISTRACTOR_FACTOR * labelledPhotos)
            if (distractors >= need) return null
            return "Need at least $need other stored photos to search among for $labelledPhotos labelled photos; found $distractors other photos."
        }

        fun refusal(photos: Int, queries: List<RecallAtK.Query>): String? {
            val small = queries.count { it.small }
            if (photos >= MIN_PHOTOS && queries.size >= MIN_QUERIES && small >= MIN_SMALL) return null
            return "Need at least $MIN_PHOTOS photos, $MIN_QUERIES queries and $MIN_SMALL small-object queries; found $photos photos, ${queries.size} queries, $small small."
        }

        /** On-screen summary lines: numbers only. */
        fun lines(d: Outcome.Done): List<String> = listOf(
            line(d.r768), line(d.r256),
            "Corpus: ${d.labelled} labelled + ${d.distractors} other photos",
            "Drop at 256: overall ${fmt((d.r768.overall - d.r256.overall) * 100)} points (limit 3), small ${fmt((d.r768.small - d.r256.small) * 100)} points (limit 5)",
            when {
                d.inconclusive -> "INCONCLUSIVE \u2014 768 already scores 95% or more, so this set can't tell the widths apart. Keep 768; add harder queries or more photos"
                d.pass256 -> "PASS 256 \u2014 store images at 256"
                else -> "KEEP 768 \u2014 leave the image width at 768"
            },
            "Cutting the image vector to 256 is not a documented Nomic property (only the text side is); this is evidence from your own photos only.",
        )

        private fun line(r: RecallAtK.Result) =
            "${r.dims}: recall@3 ${fmt(r.overall * 100)}% overall · ${fmt(r.small * 100)}% small (${r.queries} queries, ${r.smallQueries} small, ${r.photos} photos)"

        private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    }
}
