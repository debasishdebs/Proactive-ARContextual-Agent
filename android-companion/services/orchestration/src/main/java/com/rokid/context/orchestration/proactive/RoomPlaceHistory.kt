package com.rokid.context.orchestration.proactive

import androidx.sqlite.db.SimpleSQLiteQuery
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.orchestration.proactive.generators.MarkInfo
import com.rokid.context.orchestration.proactive.generators.PinInfo
import com.rokid.context.orchestration.proactive.generators.PlaceHistorySource
import com.rokid.context.orchestration.proactive.generators.PlaceVisit

/**
 * The F2 inputs from the phone's own stores, read-only, no schema change: `type='place'` memories (id `rokid_place_<frameId>`, tags `place wd:q... kind parent`, text "Looked at <name> (<parent>) ..."),
 * the user's pins ([pins]) and the marks (`canvas_label`) made on those photos. Only readable memories count (`reviewState = 'OK'`). A failed read answers empty.
 */
class RoomPlaceHistory(private val db: ContextDatabase, private val pins: () -> List<PinInfo>) : PlaceHistorySource {
    override suspend fun visits(untilMs: Long, limit: Int): List<PlaceVisit> = try {
        val pinList = pins()
        val out = ArrayList<PlaceVisit>()
        db.query(SimpleSQLiteQuery("SELECT id, frameId, text, tags, confidence, createdAt FROM memory WHERE type = 'place' AND reviewState = 'OK' AND createdAt <= ? ORDER BY createdAt DESC LIMIT ?", arrayOf(untilMs, limit))).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(2); val tags = c.getString(3).split(' ')
                val (name, parent) = parse(text)
                val key = tags.firstOrNull { it.startsWith("wd:") }?.let { "wd:" + it.removePrefix("wd:").uppercase() }
                    ?: pinList.firstOrNull { it.name.equals(name, true) }?.id ?: continue // a place with neither a Wikidata tag nor a pin has no stable key: not a candidate
                out += PlaceVisit(c.getString(0), if (c.isNull(1)) null else c.getString(1), key, name, parent, c.getLong(5), c.getDouble(4))
            }
        }
        out
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }

    override suspend fun pins(): List<PinInfo> = try { pins.invoke() } catch (e: Exception) { emptyList() }

    override suspend fun marksOnFrames(frameIds: Collection<String>): List<MarkInfo> = try {
        frameIds.distinct().chunked(400).flatMap { chunk ->
            val out = ArrayList<MarkInfo>()
            db.query(SimpleSQLiteQuery("SELECT labelId, frameId, atMs FROM canvas_label WHERE frameId IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())).use { c ->
                while (c.moveToNext()) out += MarkInfo(c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getLong(2))
            }
            out
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }

    companion object {
        /** "Looked at Golconda Fort (Hyderabad) · photo match 0.91" -> ("Golconda Fort", "Hyderabad"). */
        fun parse(text: String): Pair<String, String?> {
            val body = text.removePrefix("Looked at ").substringBefore(" · ")
            val open = body.lastIndexOf(" (")
            return if (open > 0 && body.endsWith(")")) body.substring(0, open) to body.substring(open + 2, body.length - 1) else body to null
        }
    }
}
