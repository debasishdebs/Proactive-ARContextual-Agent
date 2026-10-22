package com.rokid.context.canvas.plan

import com.rokid.context.memory.db.CanvasSlice3Dao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The recorded outcome of milestone M1 (the controller's worn device run: exact parity and `shOpen` within 5 % of the counterfactual). Nothing that can send a TRIAL or LIVE
 * plan to the glasses runs without it (S11); SHADOW does not need it. It is recorded ONLY by an explicit call (Developer-mode route `POST /api/plans/m1-evidence`), never inferred.
 */
data class M1Evidence(val passedAtMs: Long, /** A short free note (at most [MAX_NOTE] chars): the run's date or build, never a secret. */ val note: String) {
    companion object { const val MAX_NOTE = 80 }
}

interface M1EvidenceStore {
    suspend fun get(): M1Evidence?
    suspend fun record(e: M1Evidence)
}

/** `canvas_meta` key `m1Passed` (kept by Delete all: it holds no user data and describes the build, not the user). */
class DaoM1EvidenceStore(private val dao: CanvasSlice3Dao) : M1EvidenceStore {
    override suspend fun get(): M1Evidence? {
        val o = runCatching { Json.parseToJsonElement(dao.meta(KEY) ?: return null) as? JsonObject }.getOrNull() ?: return null
        val at = (o["passedAtMs"] as? JsonPrimitive)?.longOrNull ?: return null   // an unreadable record is NOT a pass
        return M1Evidence(at, (o["note"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    override suspend fun record(e: M1Evidence) {
        require(e.note.length <= M1Evidence.MAX_NOTE) { "note" }
        dao.putMeta(KEY, buildJsonObject { put("passedAtMs", e.passedAtMs); put("note", e.note) }.toString())
    }

    companion object { const val KEY = "m1Passed" }
}
