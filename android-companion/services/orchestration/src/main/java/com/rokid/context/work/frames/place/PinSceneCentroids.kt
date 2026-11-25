package com.rokid.context.work.frames.place

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.retrieval.VectorCodec

/**
 * v18 WP12b: reads the CURRENT Scene centroid of each user pin (pin -> its Moment -> the Scene holding that Moment -> `scene_centroid` of the asked space, only when its `membershipRev` equals the Scene's). Read only,
 * derived data, nothing leaves the process; the vectors are used for one score and dropped. A pin without a Moment, a Scene or a current row of that space is simply absent.
 */
class PinSceneCentroids(private val db: ContextDatabase, private val pinMoments: () -> Map<String, String>) {
    suspend fun read(spaceId: String, pinIds: List<String>): CentroidShadow.PinCentroids = db.withTransaction { readIn(spaceId, pinIds) } // revision and rows in one read
    private suspend fun readIn(spaceId: String, pinIds: List<String>): CentroidShadow.PinCentroids {
        val moments = pinMoments().filterKeys { it in pinIds.toSet() }
        if (moments.isEmpty()) return CentroidShadow.PinCentroids(emptyMap())
        val sceneOfMoment = moments.values.distinct().chunked(CHUNK).flatMap { db.sceneDao().momentPartsOf(it) }.associate { it.refId to it.sceneId }
        val revs = sceneOfMoment.values.distinct().chunked(CHUNK).flatMap { db.sceneDao().scenesByIds(it) }.associate { it.sceneId to it.membershipRev }
        val out = HashMap<String, FloatArray>()
        for ((pin, moment) in moments) {
            val sceneId = sceneOfMoment[moment] ?: continue
            val row = db.sceneCentroidDao().get(sceneId, spaceId) ?: continue
            if (revs[sceneId] != row.membershipRev || row.vector.size != row.dim * 4) continue
            val v = try { VectorCodec.decode(row.vector) } catch (_: IllegalArgumentException) { continue }
            if (v.all(Float::isFinite)) out[pin] = v
        }
        return CentroidShadow.PinCentroids(out)
    }

    private companion object { const val CHUNK = 500 }
}
