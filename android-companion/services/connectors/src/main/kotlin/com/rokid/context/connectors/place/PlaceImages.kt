package com.rokid.context.connectors.place

import com.rokid.context.memory.place.ImageState
import com.rokid.context.memory.place.PlaceImageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * B2 (design 2026-11-27 section 4): reference photos of seeded places, fetched at seed time while online and used offline afterwards.
 *
 * WHY THE FILE NAME IS ASKED HERE AND NOT INSIDE THE SEED'S TWO CALLS: places.db has no column for a pending file name (no schema change in B2), and the photo phase must also fill areas seeded before this build. So the phase asks, per 50 places,
 * (1) Wikidata `wbgetentities` (claims + the English sitelink: P18 and the Wikipedia title), (2) Wikipedia `prop=pageimages` for those titles (the lead image, preferred) and (3) Commons `imageinfo` (licence and a 330 px thumbnail URL): three
 * requests per 50 places, never one per place. Then ONE thumbnail download per place that has a free photo. Counts only in logs; no name, title, file name or URL is ever logged.
 */
data class WikidataImageRef(val enTitle: String?, val p18: String?)

/** What Commons says about one file. [free] is the licence decision ([PlaceImageLicence.isFree]); credit fields are only meaningful when [free]. */
data class CommonsFile(
    val name: String, val thumbUrl: String?, val mime: String?, val filePage: String, val author: String?, val licence: String?, val licenceUrl: String?, val free: Boolean,
)

/** The four Wikimedia calls of the photo phase (tests fake it; production is [PlaceImageHttpSource]). */
interface PlaceImageApi {
    /** QID -> English article title and P18 file name (at most [WikidataHttpSource.BATCH] ids). */
    suspend fun wikidataRefs(qids: List<String>): Map<String, WikidataImageRef>
    /** English article title -> lead image file name (at most [PAGEIMAGE_BATCH] titles); a page without an image is absent. */
    suspend fun pageImages(titles: List<String>): Map<String, String>
    /** Canonical file name (spaces) -> Commons info (at most [COMMONS_BATCH] names). */
    suspend fun commonsFiles(names: List<String>): Map<String, CommonsFile>
    /** The thumbnail bytes (checked URL, size cap, no redirect). */
    suspend fun thumbnail(url: String): ByteArray
    companion object { const val PAGEIMAGE_BATCH = 50; const val COMMONS_BATCH = 50 }
}

/** The licences that may be stored: free ones only; anything else, or unknown, is skipped. */
object PlaceImageLicence {
    fun isFree(shortName: String?, nonFree: String?): Boolean {
        if (nonFree != null && nonFree.isNotBlank() && !nonFree.equals("false", true) && nonFree != "0") return false
        val n = shortName?.trim()?.lowercase().orEmpty()
        if (n.isEmpty()) return false
        val tokens = n.replace('-', ' ').split(' ', '/', ',').filter { it.isNotEmpty() }
        if ("nc" in tokens || "nd" in tokens) return false
        return when {
            n.startsWith("cc by") -> true // cc by, cc by-sa (any version)
            n.startsWith("cc0") || n.startsWith("cc-zero") -> true
            n.startsWith("public domain") || n == "pd" || n.startsWith("pd-") || n.startsWith("pd ") -> true
            else -> false
        }
    }

    /** The licence link is kept only when it points at a known licence host (https). */
    fun safeUrl(u: String?): String? {
        val s = u?.trim() ?: return null
        val host = runCatching { java.net.URL(s) }.getOrNull()?.takeIf { it.protocol == "https" }?.host ?: return null
        return s.takeIf { host == "creativecommons.org" || host.endsWith(".creativecommons.org") || host == "commons.wikimedia.org" || host == "en.wikipedia.org" }?.take(200)
    }

    /** The author as plain text: tags removed, a few entities decoded, white space collapsed, at most 120 characters. */
    fun plain(html: String?): String? {
        val t = html?.replace(Regex("<[^>]*>"), " ")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&nbsp;", " ")
            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() }?.take(120)
    }
}

/** The Wikimedia requests of the photo phase over the shared [PlaceHttp] (https, polite user agent, global pause, Offline only and the seed admission are its). */
class PlaceImageHttpSource(private val http: PlaceHttp, private val wiki: String = "en.wikipedia.org", private val wikidata: String = "www.wikidata.org", private val commons: String = PlaceImageRules.COMMONS_HOST) : PlaceImageApi {
    override suspend fun wikidataRefs(qids: List<String>): Map<String, WikidataImageRef> {
        if (qids.isEmpty()) return emptyMap()
        require(qids.size <= WikidataHttpSource.BATCH)
        return parseWikidata(http.get("https://$wikidata/w/api.php?action=wbgetentities&ids=${qids.joinToString("%7C")}&props=claims%7Csitelinks&sitefilter=enwiki&format=json"))
    }

    override suspend fun pageImages(titles: List<String>): Map<String, String> {
        if (titles.isEmpty()) return emptyMap()
        require(titles.size <= PlaceImageApi.PAGEIMAGE_BATCH)
        return parsePageImages(http.get("https://$wiki/w/api.php?action=query&prop=pageimages&piprop=name&pilimit=${PlaceImageApi.PAGEIMAGE_BATCH}&redirects=1&titles=${titles.joinToString("%7C") { enc(it) }}&format=json&formatversion=2"), titles)
    }

    override suspend fun commonsFiles(names: List<String>): Map<String, CommonsFile> {
        if (names.isEmpty()) return emptyMap()
        require(names.size <= PlaceImageApi.COMMONS_BATCH)
        val titles = names.joinToString("%7C") { enc("File:$it") }
        return parseCommons(http.get("https://$commons/w/api.php?action=query&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Cextmetadata&iiurlwidth=${PlaceImageRules.THUMB_WIDTH}&iiextmetadatafilter=LicenseShortName%7CLicenseUrl%7CArtist%7CNonFree&titles=$titles&format=json&formatversion=2"))
    }

    override suspend fun thumbnail(url: String): ByteArray = http.getBytes(url, PlaceImageRules.MAX_BYTES)

    companion object {
        private fun JsonElement?.obj() = this as? JsonObject
        private fun JsonElement?.arr() = this as? JsonArray
        private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** "Taj_Mahal.jpg", "File:Taj Mahal.jpg" -> "Taj Mahal.jpg": the one spelling used as a key. */
        fun canonicalFile(n: String): String = n.removePrefix("File:").replace('_', ' ').trim()

        fun parseWikidata(body: String): Map<String, WikidataImageRef> {
            val root = parseBody(body)
            if (root["error"] != null) throw SerializationException("wikidata: API error body")
            return root["entities"].obj()?.entries.orEmpty().mapNotNull { (qid, v) ->
                val e = v.obj() ?: return@mapNotNull null
                val p18 = e["claims"].obj()?.get("P18").arr()?.firstNotNullOfOrNull { it.obj()?.get("mainsnak").obj()?.get("datavalue").obj()?.get("value").str() }
                val title = e["sitelinks"].obj()?.get("enwiki").obj()?.get("title").str()
                qid to WikidataImageRef(title, p18?.let(::canonicalFile)?.takeIf { it.isNotEmpty() })
            }.toMap()
        }

        /** Input title -> lead image file name, following the API's `normalized` and `redirects` maps (an input the API rewrote still finds its page). */
        fun parsePageImages(body: String, titles: List<String>): Map<String, String> {
            val q = WikipediaHttpSource.requireAnswer(parseBody(body), "pageimages").get("query").obj() ?: return emptyMap()
            val byTitle = q["pages"].arr().orEmpty().mapNotNull { p -> val o = p.obj() ?: return@mapNotNull null; (o["title"].str() ?: return@mapNotNull null) to o["pageimage"].str() }.toMap()
            fun map(key: String) = q[key].arr().orEmpty().mapNotNull { m -> val o = m.obj() ?: return@mapNotNull null; (o["from"].str() ?: return@mapNotNull null) to (o["to"].str() ?: return@mapNotNull null) }.toMap()
            val norm = map("normalized"); val redir = map("redirects")
            val out = HashMap<String, String>()
            for (t in titles) {
                val file = byTitle[redir[norm[t] ?: t] ?: (norm[t] ?: t)] ?: continue
                out[t] = canonicalFile(file)
            }
            return out
        }

        fun parseCommons(body: String): Map<String, CommonsFile> {
            val q = WikipediaHttpSource.requireAnswer(parseBody(body), "commons").get("query").obj() ?: return emptyMap()
            val out = HashMap<String, CommonsFile>()
            for (p in q["pages"].arr().orEmpty()) {
                val o = p.obj() ?: continue
                if (o["missing"] != null) continue
                val title = o["title"].str() ?: continue
                val info = o["imageinfo"].arr()?.firstOrNull().obj() ?: continue
                val meta = info["extmetadata"].obj()
                fun m(k: String) = meta?.get(k).obj()?.get("value").str()
                val name = canonicalFile(title)
                val page = info["descriptionurl"].str()?.takeIf { runCatching { java.net.URL(it) }.getOrNull()?.let { u -> u.protocol == "https" && u.host == PlaceImageRules.COMMONS_HOST } == true }
                    ?: "https://${PlaceImageRules.COMMONS_HOST}/wiki/File:" + name.replace(' ', '_')
                // Commons now answers thumbnail links on thumb.wikimedia.org; the same path is served by the upload host, which stays the only host we download from.
                val thumb = info["thumburl"].str()?.let { if (it.startsWith("https://thumb.wikimedia.org/")) "https://${PlaceImageRules.UPLOAD_HOST}/" + it.removePrefix("https://thumb.wikimedia.org/") else it }?.takeIf { runCatching { java.net.URL(it) }.getOrNull()?.let { u -> u.protocol == "https" && u.host == PlaceImageRules.UPLOAD_HOST } == true }
                val licence = m("LicenseShortName")
                out[name] = CommonsFile(name, thumb, info["mime"].str(), page, PlaceImageLicence.plain(m("Artist")), licence?.take(60), PlaceImageLicence.safeUrl(m("LicenseUrl")), PlaceImageLicence.isFree(licence, m("NonFree")))
            }
            return out
        }
    }
}

/** What a downloaded thumbnail was after the decode check: the JPEG to store and its size. */
class Thumb(val jpeg: ByteArray, val width: Int, val height: Int)

/** Decodes a picture and, when it is not a JPEG or longer than [PlaceImageRules.MAX_SIDE], re-encodes it as a JPEG. Null = not decodable. The phone supplies the Android one; tests a fake. */
fun interface ThumbCodec { fun normalize(raw: ByteArray): Thumb? }

object ImageMagic {
    /** JPEG, PNG or WebP by its first bytes (never by the server's say-so). */
    fun isImage(b: ByteArray): Boolean = isJpeg(b) || isPng(b) || isWebp(b)
    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()
    fun isWebp(b: ByteArray) = b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
}

/** A seeded place the phase has to look at: its id and how many times it already failed. */
data class PhotoCandidate(val id: String, val priorTries: Int)

/** Thrown by the fetcher when the server asks us to slow down or the network failed: nothing was stored for the place, the caller waits (the shared guard already holds the pause) and asks again later. */
class PhotoPhaseRetry(cause: Throwable) : java.io.IOException("photo phase: try later", cause)

/**
 * How many thumbnails may download at once from the image host: up to [max] (4), and ONE after the server said 429/503 (the shared guard holds the Retry-After pause for every request of the group), then one more after every
 * [CLEAN_BATCHES_PER_STEP] batches that went through without being slowed down. Counts only.
 */
class DownloadConcurrency(val max: Int = MAX) {
    private var now = max
    private var clean = 0
    val limit: Int @Synchronized get() = now
    @Synchronized fun onThrottle() { now = 1; clean = 0 }
    @Synchronized fun onCleanBatch() { if (now < max && ++clean >= CLEAN_BATCHES_PER_STEP) { now++; clean = 0 } }
    companion object { const val MAX = 2; const val CLEAN_BATCHES_PER_STEP = 2 }
}

/**
 * One batch of the photo phase: places -> Wikidata -> Wikipedia lead image (P18 as fallback) -> Commons licence -> the thumbnail downloads of the places that have a free photo, [DownloadConcurrency] at a time (3-4 connections, one after a 429/503). Each decided place is handed to [store] at once, so a stop (process death,
 * Wi-Fi lost, Offline only) never loses what was done. Status per place: OK (photo stored), NONE (no free photo exists / no Wikidata item), SKIPPED (mime, licence, size), FAILED (this place's download or decode failed; [tries] counts, [MAX_TRIES] ends it).
 * A 429/503, a timeout or any other transport failure is NOT a place failure: it throws [PhotoPhaseRetry] without burning a try. Stops (cancellation, Offline only, the admission) propagate untouched.
 */
class PlaceImageFetcher(
    private val api: PlaceImageApi,
    private val codec: ThumbCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val concurrency: DownloadConcurrency = DownloadConcurrency(),
) {
    suspend fun fetch(batch: List<PhotoCandidate>, store: suspend (PlaceImageEntity) -> Unit): Counts {
        require(batch.size <= WikidataHttpSource.BATCH)
        var ok = 0; var none = 0; var skipped = 0; var failed = 0
        suspend fun put(c: PhotoCandidate, status: String, tries: Int = c.priorTries, thumb: Thumb? = null, f: CommonsFile? = null) {
            val row = PlaceImageEntity(
                c.id, status, tries, thumb?.jpeg, thumb?.width, thumb?.height, thumb?.jpeg?.let(::sha1), f?.filePage.takeIf { thumb != null }, f?.author.takeIf { thumb != null }, f?.licence.takeIf { thumb != null }, f?.licenceUrl.takeIf { thumb != null },
                null, null, null, null, null, clock(), thumb?.jpeg?.size ?: 0,
            )
            store(row)
            when (status) { ImageState.OK -> ok++; ImageState.NONE -> none++; ImageState.SKIPPED -> skipped++; else -> failed++ }
        }
        try {
            val qidOf = batch.associateWith { if (it.id.startsWith("wd:Q")) it.id.removePrefix("wd:") else null }
            for ((c, q) in qidOf) if (q == null) put(c, ImageState.NONE) // an OSM-only place has no Wikidata item here
            val refs = api.wikidataRefs(qidOf.values.filterNotNull().distinct())
            val titles = qidOf.values.mapNotNull { refs[it]?.enTitle }.distinct()
            val lead = titles.chunked(PlaceImageApi.PAGEIMAGE_BATCH).fold(emptyMap<String, String>()) { acc, t -> acc + api.pageImages(t) }
            val fileOf = LinkedHashMap<PhotoCandidate, String>()
            for ((c, q) in qidOf) {
                if (q == null) continue
                val r = refs[q]
                val f = r?.enTitle?.let { lead[it] } ?: r?.p18
                if (f == null) put(c, ImageState.NONE) else fileOf[c] = f
            }
            val info = fileOf.values.distinct().chunked(PlaceImageApi.COMMONS_BATCH).fold(emptyMap<String, CommonsFile>()) { acc, n -> acc + api.commonsFiles(n) }
            val todo = ArrayList<Pair<PhotoCandidate, CommonsFile>>()
            for ((c, file) in fileOf) {
                val f = info[file]
                when {
                    f == null -> put(c, ImageState.NONE) // the file is gone on Commons
                    !f.free -> put(c, ImageState.SKIPPED)
                    f.mime != "image/jpeg" || f.thumbUrl == null -> put(c, ImageState.SKIPPED) // maps, flags and logos are SVG or PNG: JPEG photos only
                    else -> todo += c to f
                }
            }
            val lock = Mutex() // the store and the counters are used by one download at a time
            val finished = HashSet<String>()
            val locked: suspend (PhotoCandidate, String, Int, Thumb?, CommonsFile?) -> Unit = { c, st, t, th, f -> lock.withLock { put(c, st, t, th, f); finished += c.id } }
            var rest: List<Pair<PhotoCandidate, CommonsFile>> = todo
            var throttled = 0
            while (rest.isNotEmpty()) {
                val wave = rest.take(concurrency.limit.coerceAtLeast(1))
                // A 429/503 on one download does not cancel the others of the wave: they finish and are stored; only the refused ones are asked again.
                val refused = coroutineScope {
                    wave.map { (c, f) -> async { try { download(c, f, locked); null } catch (e: PlaceHttpException) { if (e.code == 429 || e.code == 503) e else throw e } } }.awaitAll()
                }.firstOrNull { it != null }
                if (refused == null) { rest = rest.drop(wave.size); throttled = 0; continue }
                // The server said "slow down": go to one connection and carry on in this run. The next request waits out the Retry-After in the shared guard; only a long run of refusals hands the batch back to the job retry.
                concurrency.onThrottle()
                if (++throttled > MAX_THROTTLES_IN_A_ROW) throw refused
                rest = rest.filter { it.first.id !in finished }
            }
            concurrency.onCleanBatch()
        } catch (e: CancellationException) { throw e
        } catch (e: PhotoPhaseRetry) { throw e
        } catch (e: com.rokid.context.memory.place.PlaceOfflineOnly) { throw e
        } catch (e: com.rokid.context.memory.place.ZoneFenced) { throw e
        } catch (e: SerializationException) { throw PhotoPhaseRetry(e) // an unreadable answer is the server's, not the place's
        } catch (e: java.io.IOException) { throw PhotoPhaseRetry(e) }
        return Counts(ok, none, skipped, failed)
    }

    private suspend fun download(c: PhotoCandidate, f: CommonsFile, put: suspend (PhotoCandidate, String, Int, Thumb?, CommonsFile?) -> Unit) {
        val raw = try { api.thumbnail(f.thumbUrl!!) } catch (e: PlaceTooLarge) { return put(c, ImageState.SKIPPED, c.priorTries, null, null)
        } catch (e: PlaceHttpException) {
            if (e.code in intArrayOf(404, 403, 410, 400)) return put(c, ImageState.FAILED, c.priorTries + 1, null, null)
            throw e
        } catch (e: IllegalArgumentException) { return put(c, ImageState.FAILED, c.priorTries + 1, null, null) } // a URL the allow-list refused
        if (!ImageMagic.isImage(raw)) return put(c, ImageState.FAILED, c.priorTries + 1, null, null)
        val thumb = try { codec.normalize(raw) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (thumb == null || thumb.jpeg.isEmpty() || thumb.jpeg.size > PlaceImageRules.MAX_BYTES || !ImageMagic.isJpeg(thumb.jpeg)) return put(c, ImageState.FAILED, c.priorTries + 1, null, null)
        put(c, ImageState.OK, 0, thumb, f)
    }

    data class Counts(val ok: Int, val none: Int, val skipped: Int, val failed: Int)

    companion object {
        /** A place that failed this many times is left alone (needsFetch no longer returns it). */
        const val MAX_TRIES = 3
        /** Refusals (429/503) in a row, with nothing downloaded in between, before the batch is handed back to the job retry. */
        const val MAX_THROTTLES_IN_A_ROW = 6
        fun sha1(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** Why [PlaceImagePhase.run] returned. */
enum class PhotoEnd { DONE, CAP_REACHED }

/**
 * The resumable photo phase over the pack: asks the pack's own queue ([com.rokid.context.memory.place.PlacePack.needsFetch]: seeded places without a row, FAILED with tries left, a trimmed thumbnail without a vector of the active space), so the same code fills
 * a fresh seed AND the areas seeded before this build (catch-up), and a process death loses nothing (every decided place is already a row). One batch of 50 at a time; [onBatch] gets the totals for the progress line.
 */
class PlaceImagePhase(
    private val pack: com.rokid.context.memory.place.PlacePack,
    private val fetcher: PlaceImageFetcher,
    private val space: suspend () -> String,
    private val batch: Int = WikidataHttpSource.BATCH,
    private val capBytes: Long = com.rokid.context.memory.place.PlacePack.IMAGE_CAP_BYTES,
) {
    suspend fun run(onBatch: suspend (com.rokid.context.memory.place.PhotoTotals) -> Unit = {}): PhotoEnd {
        while (true) {
            if (pack.imageStats().bytes >= capBytes) { pack.trimImages(capBytes); if (pack.imageStats().bytes >= capBytes) return PhotoEnd.CAP_REACHED } // embedded thumbnails can go first: the cap is a budget, not a stop
            val ids = pack.needsFetch(space(), PlaceImageFetcher.MAX_TRIES, batch)
            if (ids.isEmpty()) return PhotoEnd.DONE
            val prior = pack.imagesOf(ids).associate { it.placeId to it.tries }
            var stored = 0
            fetcher.fetch(ids.map { PhotoCandidate(it, prior[it] ?: 0) }) { row -> stored += pack.putImages(listOf(row)) }
            onBatch(pack.photoTotals())
            if (stored == 0) return PhotoEnd.DONE // nothing could be stored (the places are gone): never loop on the same ids
        }
    }
}
