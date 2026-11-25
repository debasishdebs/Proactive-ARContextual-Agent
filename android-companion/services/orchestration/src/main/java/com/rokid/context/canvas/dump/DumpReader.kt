package com.rokid.context.canvas.dump

import com.rokid.context.memory.db.AttributionKind
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.ModelAttributionEntity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DumpVectorRow
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/**
 * v1.7 Developer dump API (design 2026-11-26): the READ-ONLY reader behind the `/api/dump` routes. It is the ONE reader allowed to put the saved Moment location (and the pin coordinates) into an answer: the owner asked for them for experiments
 * on his own phone, behind Developer mode, the `devDump` switch and a 10-minute arming. `MomentLocationSourceRulesTest` lists this file explicitly and still forbids every other path (export, logs, prompts, Ask, the canvas routes).
 *
 * Reads only: existing DAOs and the new SELECT-only `DumpDao`. A forgotten (tombstoned) Moment or photo never appears. Never included: SQLCipher keys, pairing tokens, API keys, settings, the embedding space's config JSON.
 * [pinSource] reads the user's pins (prefs `place_pins`, injected so a test needs no Android prefs); [appVersion] and [clock] feed the manifest.
 */
class DumpReader(
    private val db: ContextDatabase,
    private val pinSource: () -> List<UserPlace> = { emptyList() },
    private val appVersion: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
    private val readPhoto: (String) -> ByteArray? = FrameImages::readBytes,
) : DumpSource {
    private val dump = db.dumpDao()

    override suspend fun manifest(): JsonObject {
        val moments = dump.momentCount(); val scenes = dump.sceneCount(); val marks = dump.markCount(); val frames = dump.momentFrameCount()
        val locations = db.momentLocationDao().count(); val pinList = pinSource(); val attributed = db.modelAttributionDao().count()
        val first = dump.firstMomentId(); val last = dump.lastMomentId()
        val spaces = dump.spaces()
        val memorySpaces = dump.memoryVectorSpaces(); val frameSpaces = dump.frameVectorSpaces()
        val nomicText = memorySpaces.sumOf { dump.memoryVectorCount(it) }
        val nomicImage = frameSpaces.filter { !it.startsWith(SIGLIP) }.sumOf { dump.frameVectorCount(it) }
        val siglip = frameSpaces.filter { it.startsWith(SIGLIP) }.sumOf { dump.frameVectorCount(it) }
        val schema = try { db.openHelper.readableDatabase.version } catch (e: Exception) { -1 }
        // the id names the CONTENT: the same data gives the same id, so a client can resume a folder; counts and the id range, never text
        val dumpId = "dump-" + sha(listOf(moments, scenes, marks, frames, locations, attributed, pinList.size, first, last, nomicText, nomicImage, siglip).joinToString("|")).take(10)
        return buildJsonObject {
            put("dump_id", dumpId); put("generatedAtMs", clock()); put("appVersion", appVersion); put("contextDbSchema", schema)
            put("counts", buildJsonObject {
                put("moments", moments); put("scenes", scenes); put("marks", marks); put("pins", pinList.size); put("frames", frames); put("locations", locations); put("modelAttributions", attributed)
                put("vectors_nomic_text", nomicText); put("vectors_nomic_image", nomicImage); put("vectors_siglip2", siglip)
            })
            put("range", buildJsonObject {
                put("firstMomentId", first); put("lastMomentId", last)
                put("firstMs", first?.let(MomentId::startMs)); put("lastMs", last?.let { MomentId.startMs(it)?.plus(999) })
            })
            put("embeddingSpaces", JsonArray(spaces.map { buildJsonObject { put("id", it.id); put("name", it.fingerprint); put("dim", it.dim); put("state", it.state.name); put("createdAt", it.createdAt) } }))
            put("vectorSpaces", buildJsonObject {
                put("nomic", buildJsonObject { put("textSpaceIds", JsonArray(memorySpaces.map(::JsonPrimitive))); put("imageSpaceIds", JsonArray(frameSpaces.filter { !it.startsWith(SIGLIP) }.map(::JsonPrimitive))); put("dtype", "float32") })
                put("siglip2", buildJsonObject { put("imageSpaceIds", JsonArray(frameSpaces.filter { it.startsWith(SIGLIP) }.map(::JsonPrimitive))); put("dtype", "float32") })
            })
            put("fields", buildJsonObject {
                put("moment", JsonArray(listOf("id", "startMs", "endMs", "type", "text", "transcript", "errorReason", "noPhotoReason", "buildRev", "contentRev", "contentHash", "builtAt", "frames", "memories", "marks", "derived", "reviewState", "placeName", "placeNames", "placeCards", "sceneId", "location").map(::JsonPrimitive)))
                put("location", JsonArray(listOf("lat", "lon", "accuracyM", "source", "speedClass", "method", "atMs", "fixAgeMs").map(::JsonPrimitive)))
                put("scene", JsonArray(listOf("id", "startMs", "endMs", "hourStartMs", "boundaryRule", "startTrigger", "endTrigger", "activity", "membershipRev", "buildRev", "builtAt", "momentIds", "derived", "clusters").map(::JsonPrimitive)))
                put("mark", JsonArray(listOf("id", "atMs", "source", "name", "frameId", "momentId", "photo", "createdAt").map(::JsonPrimitive)))
                put("pin", JsonArray(listOf("id", "name", "kind", "lat", "lon", "radiusM", "parentId", "note", "momentId", "pinnedAt").map(::JsonPrimitive)))
            })
            // not stored in context.db, so not in the dump: the audio transcript always; which model wrote a caption or summary only while no `model_attribution` row exists (v18 WP11a records them from now on; older text has none)
            put("fieldsAbsent", JsonArray((if (attributed == 0) listOf("model_name", "model_version", "transcript_text") else listOf("transcript_text")).map(::JsonPrimitive)))
        }
    }

    override suspend fun moments(fromMs: Long, toMs: Long, after: String?, limit: Int): List<DumpItem> {
        val fromId = idAtOrNull(fromMs) ?: MIN_ID; val toId = idAtOrNull(toMs) ?: MAX_ID
        val rows = dump.momentsPage(fromId, toId, after ?: "", limit.coerceIn(1, DumpSource.MAX_PAGE_ITEMS))
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.momentId }
        val parts = ids.chunked(CHUNK).flatMap { dump.partsOfMoments(it) }.groupBy { it.momentId }
        val frameIds = parts.values.flatten().filter { it.kind == MomentPartKind.FRAME }.map { it.refId }.distinct()
        val memoryIds = parts.values.flatten().filter { it.kind == MomentPartKind.MEMORY }.map { it.refId }.distinct()
        val frames = frameIds.chunked(CHUNK).flatMap { dump.framesByIds(it) }.associateBy { it.id }
        val metas = frameIds.chunked(CHUNK).flatMap { dump.frameMetaByIds(it) }.associateBy { it.frameId }
        val memories = memoryIds.chunked(CHUNK).flatMap { dump.memoriesByIds(it) }.associateBy { it.id }
        val derived = ids.chunked(CHUNK).flatMap { dump.derivedOf(ObjectKind.MOMENT, it) }.groupBy { it.objectId }
        val models = Models(modelsOf(AttributionKind.MEMORY, memoryIds), modelsOf(AttributionKind.DERIVED, derived.values.flatten().map { AttributionKind.derivedRef(it.objectKind, it.objectId, it.field) }))
        val cards = frameIds.chunked(CHUNK).flatMap { dump.cardLogOfFrames(it) }.groupBy { it.frameId }
        val scenes = ids.chunked(CHUNK).flatMap { dump.scenePartsHolding(it) }.associate { it.refId to it.sceneId }
        val locations = db.momentLocationDao().of(ids).associateBy { it.momentId }
        val pinList = pinSource()
        val markRows = ids.flatMap { id -> parts[id].orEmpty().filter { it.kind == MomentPartKind.MARK }.map { it.refId } }.distinct()
        val labels = markRows.chunked(CHUNK).flatMap { dump.labelsByIds(it) }.associateBy { it.labelId }
        return rows.map { m ->
            val mp = parts[m.momentId].orEmpty()
            val fIds = mp.filter { it.kind == MomentPartKind.FRAME }.map { it.refId }.filter { it in frames }
            val mems = mp.filter { it.kind == MomentPartKind.MEMORY }.mapNotNull { memories[it.refId] }
            DumpItem(m.momentId, momentJson(m, mp, fIds, frames, metas, mems, derived[m.momentId].orEmpty(), fIds.flatMap { cards[it].orEmpty() }, scenes[m.momentId], locations[m.momentId], pinList, mp.filter { it.kind == MomentPartKind.MARK }.mapNotNull { labels[it.refId] }, models))
        }
    }

    private fun momentJson(
        m: MomentEntity, parts: List<MomentPartEntity>, frameIds: List<String>, frames: Map<String, com.rokid.context.memory.db.FrameEntity>, metas: Map<String, com.rokid.context.memory.db.FrameMetaEntity>,
        mems: List<MemoryEntity>, derived: List<ObjectDerivedEntity>, cards: List<com.rokid.context.memory.db.PlaceCardLogEntity>, sceneId: String?, loc: MomentLocationEntity?, pinList: List<UserPlace>, marks: List<CanvasLabelEntity>, models: Models,
    ): JsonObject {
        val start = MomentId.startMs(m.momentId) ?: 0L
        val review = when { mems.any { it.reviewState == ReviewState.INVALIDATED } -> "INVALIDATED"; mems.any { it.reviewState == ReviewState.NEEDS_REVIEW || it.reviewFlaggedAt != null } -> "NEEDS_REVIEW"; mems.isNotEmpty() -> "OK"; else -> null }
        return buildJsonObject {
            put("id", m.momentId); put("startMs", start); put("endMs", start + 999)
            put("type", m.momentType.name); put("text", m.text); put("transcript", m.transcript); put("errorReason", m.errorReason); put("noPhotoReason", m.noPhotoReason?.name)
            put("buildRev", m.buildRev); put("contentRev", m.contentRev); put("contentHash", m.contentHash); put("builtAt", m.builtAt)
            put("frames", JsonArray(frameIds.map { id ->
                val f = frames.getValue(id); val x = metas[id]
                buildJsonObject {
                    put("id", id); put("ts", f.ts); put("source", f.source); put("capturedAtMs", x?.capturedAtMs); put("markRef", x?.markRef); put("clusterId", x?.clusterId)
                    put("motion", x?.motion); put("dwell", x?.dwell); put("blur", x?.blur); put("salience", x?.salience); put("width", x?.width); put("phash", f.phash ?: x?.phash); put("dhash", x?.dhash)
                    put("nearDupOf", x?.nearDupOf); put("intentLabel", x?.intentLabel); put("intentConf", x?.intentConf); put("imageVectorSpace", x?.imageVectorSpace); put("placeLabel", f.placeLabel)
                    put("hasPhoto", f.path.isNotBlank())
                }
            }))
            put("memories", JsonArray(mems.map { e ->
                buildJsonObject {
                    put("id", e.id); put("frameId", e.frameId); put("text", e.text); put("type", e.type); put("tags", e.tags); put("tier", e.tier.name); put("confidence", e.confidence)
                    put("reviewState", e.reviewState.name); put("reviewFlaggedAt", e.reviewFlaggedAt); put("imageDelivered", e.imageDelivered); put("derived", e.derived); put("createdAt", e.createdAt)
                    val a = models.memory[e.id]; put("model", a?.model); put("modelVersion", a?.modelVersion) // v18 WP11a: null when no `model_attribution` row exists (older text)
                }
            }))
            put("marks", JsonArray(marks.map { l -> buildJsonObject { put("id", l.labelId); put("source", l.source); put("name", l.name); put("atMs", l.atMs); put("frameId", l.frameId); put("photo", l.photo) } }))
            put("derived", derivedJson(derived, models.derived))
            put("reviewState", review)
            // the resolved place name when one exists, apart from the coordinates: a pin attached to this Moment, a pin a card named, else the card's place title (the pack place the card was made for)
            val names = ArrayList<JsonObject>()
            pinList.filter { it.momentId == m.momentId }.forEach { p -> names += placeName("pin", p.id, p.name) }
            cards.forEach { c -> val pin = pinList.firstOrNull { it.id == c.placeId }; names += if (pin != null) placeName("pin", pin.id, pin.name) else placeName("card", c.placeId, c.title) }
            put("placeName", names.firstOrNull()?.get("name") ?: JsonNull)
            put("placeNames", JsonArray(names.distinct()))
            put("placeCards", JsonArray(cards.map { c -> buildJsonObject { put("id", c.id); put("cardId", c.cardId); put("placeId", c.placeId); put("title", c.title); put("kind", c.kind); put("delivery", c.delivery); put("at", c.at); put("frameId", c.frameId) } }))
            put("sceneId", sceneId)
            put("location", loc?.let { l -> buildJsonObject { put("lat", l.lat); put("lon", l.lon); put("accuracyM", l.accuracyM); put("source", l.source); put("speedClass", l.speedClass); put("method", l.method); put("atMs", l.atMs); put("fixAgeMs", l.fixAgeMs) } } ?: JsonNull)
        }
    }

    /** The attribution rows the dump reads (v18 WP11a), by item: memories by id, derived fields by `objectKind:objectId:field`. Names only. */
    private class Models(val memory: Map<String, ModelAttributionEntity>, val derived: Map<String, ModelAttributionEntity>)

    private suspend fun modelsOf(kind: String, refs: List<String>): Map<String, ModelAttributionEntity> =
        refs.distinct().chunked(CHUNK).flatMap { db.modelAttributionDao().ofRefs(kind, it) }.associateBy { it.refId }

    private fun placeName(source: String, placeId: String, name: String) = buildJsonObject { put("source", source); put("placeId", placeId); put("name", name) }

    /** A derived field with the build revision that wrote it, the model that wrote it (null when no attribution row exists) and whether it is stale; the EMBEDDING row names its space in [ref]. */
    private fun derivedJson(rows: List<ObjectDerivedEntity>, models: Map<String, ModelAttributionEntity>) = JsonArray(rows.map { d ->
        val a = models[AttributionKind.derivedRef(d.objectKind, d.objectId, d.field)]
        buildJsonObject { put("field", d.field); put("value", d.value); put("embeddingSpace", if (d.field == "EMBEDDING") d.ref else null); put("builtRev", d.builtRev); put("atMs", d.atMs); put("stale", d.staleSince != null); put("model", a?.model); put("modelVersion", a?.modelVersion) }
    })

    override suspend fun scenes(fromMs: Long, toMs: Long, after: String?, limit: Int): List<DumpItem> {
        val (key, id) = splitCursor(after) ?: (Long.MIN_VALUE to "")
        val rows = dump.scenesPage(fromMs, toMs, key, id, limit.coerceIn(1, DumpSource.MAX_PAGE_ITEMS))
        if (rows.isEmpty()) return emptyList()
        val sceneIds = rows.map { it.sceneId }
        val parts = sceneIds.chunked(CHUNK).flatMap { db.sceneDao().partsOf(it) }.groupBy { it.sceneId }
        val allMoments = parts.values.flatten().filter { it.kind == "MOMENT" }.map { it.refId }.distinct()
        val dead = allMoments.chunked(CHUNK).flatMap { dump.tombstonedMoments(it) }.toSet()
        val derived = sceneIds.chunked(CHUNK).flatMap { dump.derivedOf(ObjectKind.SCENE, it) }.groupBy { it.objectId }
        val liveMoments = allMoments.filter { it !in dead }
        val momentParts = liveMoments.chunked(CHUNK).flatMap { dump.partsOfMoments(it) }.filter { it.kind == MomentPartKind.FRAME }.groupBy { it.momentId }
        val frameIds = momentParts.values.flatten().map { it.refId }.distinct()
        val liveFrames = frameIds.chunked(CHUNK).flatMap { dump.framesByIds(it) }.map { it.id }.toSet()
        val clusterOfFrame = frameIds.chunked(CHUNK).flatMap { dump.frameMetaByIds(it) }.filter { it.frameId in liveFrames && it.clusterId != null }.associate { it.frameId to it.clusterId!! }
        val clusters = clusterOfFrame.values.distinct().chunked(CHUNK).flatMap { dump.clustersByIds(it) }.associateBy { it.clusterId }
        val clusterMemories = clusters.values.mapNotNull { it.memoryId }.distinct().chunked(CHUNK).flatMap { dump.memoriesByIds(it) }.associateBy { it.id }
        val models = Models(modelsOf(AttributionKind.MEMORY, clusterMemories.keys.toList()), modelsOf(AttributionKind.DERIVED, derived.values.flatten().map { AttributionKind.derivedRef(it.objectKind, it.objectId, it.field) }))
        return rows.map { s ->
            val members = parts[s.sceneId].orEmpty().filter { it.kind == "MOMENT" }.map { it.refId }.filter { it !in dead }
            val memberFrames = members.flatMap { momentParts[it].orEmpty() }.map { it.refId }
            val cids = memberFrames.mapNotNull { clusterOfFrame[it] }.distinct().filter { it in clusters }
            DumpItem("${s.startMs}:${s.sceneId}", sceneJson(s, members, derived[s.sceneId].orEmpty(), cids.map { clusters.getValue(it) }, clusterMemories, liveFrames, models))
        }
    }

    private fun sceneJson(s: SceneEntity, members: List<String>, derived: List<ObjectDerivedEntity>, clusters: List<com.rokid.context.memory.db.FrameClusterEntity>, memories: Map<String, MemoryEntity>, liveFrames: Set<String>, models: Models) = buildJsonObject {
        put("id", s.sceneId); put("startMs", s.startMs); put("endMs", s.endMs); put("hourStartMs", s.hourStartMs); put("boundaryRule", s.boundaryRule)
        put("startTrigger", s.startTrigger); put("endTrigger", s.endTrigger); put("activity", s.activity); put("membershipRev", s.membershipRev); put("buildRev", s.buildRev); put("builtAt", s.builtAt)
        put("momentIds", JsonArray(members.map(::JsonPrimitive)))
        put("derived", derivedJson(derived, models.derived))
        put("clusters", JsonArray(clusters.map { c ->
            val reps = try { Json.parseToJsonElement(c.repFrameIds).jsonArray.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } } catch (e: Exception) { emptyList() }
            val mem = c.memoryId?.let { memories[it] }
            buildJsonObject {
                put("clusterId", c.clusterId); put("state", c.state); put("openedAt", c.openedAt); put("closedAt", c.closedAt); put("closeReason", c.closeReason); put("motionClass", c.motionClass); put("memberCount", c.memberCount)
                put("medoidFrameIds", JsonArray(reps.filter { it in liveFrames }.map(::JsonPrimitive)))
                put("summary", mem?.text); put("summaryMemoryId", mem?.id); put("summaryReviewState", mem?.reviewState?.name)
                val a = mem?.let { models.memory[it.id] }; put("summaryModel", a?.model); put("summaryModelVersion", a?.modelVersion)
            }
        }))
    }

    override suspend fun marks(fromMs: Long, toMs: Long, after: String?, limit: Int): List<DumpItem> {
        val (key, id) = splitCursor(after) ?: (Long.MIN_VALUE to "")
        val rows = dump.marksPage(fromMs, toMs, key, id, limit.coerceIn(1, DumpSource.MAX_PAGE_ITEMS))
        if (rows.isEmpty()) return emptyList()
        val moments = rows.map { it.labelId }.chunked(CHUNK).flatMap { dump.momentsOfMarks(it) }.associate { it.refId to it.momentId }
        val liveFrames = rows.mapNotNull { it.frameId }.chunked(CHUNK).flatMap { dump.framesByIds(it) }.map { it.id }.toSet()
        return rows.map { l ->
            DumpItem("${l.atMs}:${l.labelId}", buildJsonObject {
                put("id", l.labelId); put("atMs", l.atMs); put("source", l.source); put("name", l.name); put("frameId", l.frameId?.takeIf { it in liveFrames }); put("momentId", moments[l.labelId]); put("photo", l.photo); put("createdAt", l.createdAt)
            })
        }
    }

    override suspend fun pins(): List<JsonObject> {
        val list = pinSource()
        val linked = list.mapNotNull { it.momentId }.distinct()
        val dead = linked.chunked(CHUNK).flatMap { dump.tombstonedMoments(it) }.toSet()
        return list.sortedBy { it.id }.map { p ->
            buildJsonObject {
                put("id", p.id); put("name", p.name); put("kind", p.kind); put("lat", p.lat); put("lon", p.lon); put("radiusM", p.radiusM); put("parentId", p.parentId); put("note", p.note)
                put("momentId", p.momentId?.takeIf { it !in dead }); put("pinnedAt", p.pinnedAt)
            }
        }
    }

    override suspend fun photo(frameId: String): DumpPhoto? {
        val f = dump.framesByIds(listOf(frameId)).firstOrNull() ?: return null // a tombstoned or deleted photo has no row here
        if (f.path.isBlank() || java.io.File(f.path).length() > DumpSource.MAX_PHOTO_BYTES) return null
        val bytes = readPhoto(f.path) ?: return null
        if (bytes.size > DumpSource.MAX_PHOTO_BYTES) return null
        val type = sniff(bytes) ?: return null
        if (dump.framesByIds(listOf(frameId)).isEmpty()) return null // forgotten while the file was read
        return DumpPhoto(bytes, type)
    }

    override suspend fun vectors(space: String, after: String?, limit: Int, float16: Boolean): List<DumpItem>? {
        if (space !in DumpSource.SPACES) return null
        val n = limit.coerceIn(1, DumpSource.MAX_PAGE_ITEMS)
        val (kind, rowid) = parseVectorCursor(after)
        val out = ArrayList<DumpItem>()
        if (space == "nomic" && kind == "m") {
            dump.memoryVectorsPage(rowid, n).forEach { out += vectorItem("m", "memory", it, float16) }
        }
        if (out.size < n && (kind == "m" || kind == "f")) {
            val startRow = if (kind == "f") rowid else 0L
            if (space == "siglip2" || space == "nomic") dump.frameVectorsPage(startRow, if (space == "siglip2") 1 else 0, n - out.size).forEach { out += vectorItem("f", "frame", it, float16) }
        }
        return out
    }

    private fun vectorItem(prefix: String, kind: String, r: DumpVectorRow, float16: Boolean): DumpItem {
        val dim = r.blob.size / 4
        val aligned = r.blob.size % 4 == 0
        val bytes = if (float16 && aligned) toHalf(r.blob) else r.blob
        return DumpItem("$prefix:${r.rowid}", buildJsonObject {
            put("kind", kind); put("id", r.id); put("spaceId", r.spaceId); put("dim", dim); put("dtype", if (!aligned) "raw" else if (float16) "float16" else "float32"); put("data", Base64.getEncoder().encodeToString(bytes))
        })
    }

    private fun parseVectorCursor(after: String?): Pair<String, Long> {
        if (after.isNullOrEmpty()) return "m" to 0L
        val i = after.indexOf(':'); if (i != 1) return "m" to 0L
        return after.substring(0, 1).takeIf { it == "m" || it == "f" }.let { (it ?: "m") to (after.substring(2).toLongOrNull() ?: 0L) }
    }

    private fun splitCursor(c: String?): Pair<Long, String>? {
        if (c.isNullOrEmpty()) return null
        val i = c.indexOf(':'); if (i <= 0) return null
        return (c.substring(0, i).toLongOrNull() ?: return null) to c.substring(i + 1)
    }

    private fun idAtOrNull(ms: Long): String? = try { MomentId.ofMs(ms) } catch (e: IllegalArgumentException) { null }

    companion object {
        private const val CHUNK = 400
        private const val SIGLIP = "siglip2"
        private const val MIN_ID = "00000000"
        private const val MAX_ID = "ffffffff"

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun sniff(b: ByteArray): String? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }

        /** Little-endian float32 to little-endian IEEE half (round to nearest even; overflow = infinity). */
        internal fun toHalf(blob: ByteArray): ByteArray {
            val v = VectorCodec.decode(blob)
            val out = ByteArray(v.size * 2)
            for (i in v.indices) { val h = floatToHalf(v[i]); out[2 * i] = (h and 0xFF).toByte(); out[2 * i + 1] = ((h shr 8) and 0xFF).toByte() }
            return out
        }

        internal fun floatToHalf(f: Float): Int {
            val b = java.lang.Float.floatToRawIntBits(f)
            val sign = (b ushr 16) and 0x8000
            val exp = (b ushr 23) and 0xFF
            val man = b and 0x7FFFFF
            if (exp == 0xFF) return sign or 0x7C00 or (if (man != 0) 0x200 else 0)
            val e = exp - 127 + 15
            if (e >= 0x1F) return sign or 0x7C00
            if (e <= 0) {
                if (e < -10) return sign
                val m = man or 0x800000
                val shift = 14 - e
                var h = m ushr shift
                val rem = m and ((1 shl shift) - 1); val half = 1 shl (shift - 1)
                if (rem > half || (rem == half && (h and 1) == 1)) h++
                return sign or h
            }
            var h = sign or (e shl 10) or (man ushr 13)
            val rem = man and 0x1FFF
            if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
            return h
        }
    }
}
