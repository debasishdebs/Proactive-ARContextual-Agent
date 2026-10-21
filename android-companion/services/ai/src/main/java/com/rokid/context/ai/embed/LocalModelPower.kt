package com.rokid.context.ai.embed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.Modality

/** Phone battery as the on-device model gate sees it. [batteryPct] -1 = unknown. */
data class PowerState(val batteryPct: Int, val charging: Boolean) {
    companion object {
        /** No gate (tests, and graphs built without a Context). */
        val UNGATED = PowerState(100, charging = true)
    }
}

/**
 * Gaps G6, MVP spec 6: "Local model allowed only when charging OR battery >70%", applied to on-device
 * (LLAMACPP_LOCAL) embeddings.
 *
 * Ruling (recorded in the G1/G6 report): the spec has no rule for a user's own search (MVP 6 predates on-device
 * embeddings, and "Manual readContext bypasses schedule"), so one short query embed (<= [QUERY_MAX_CHARS]) is allowed
 * at any battery: it is a single cheap call. Bulk work (reindex, canaries, backfill) and longer queries are gated;
 * a gated search falls back to lexical through the existing semantic-unavailable path.
 */
object LocalModelPower {
    const val MIN_BATTERY_PCT = 70
    const val QUERY_MAX_CHARS = 512

    fun bulkAllowed(p: PowerState): Boolean = p.charging || p.batteryPct > MIN_BATTERY_PCT

    fun queryAllowed(p: PowerState, query: String): Boolean = bulkAllowed(p) || query.length <= QUERY_MAX_CHARS

    /** Reads the battery service and the sticky battery broadcast; unknown values gate (never throws). */
    fun read(context: Context): PowerState {
        val app = context.applicationContext
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()?.takeIf { it in 0..100 } ?: -1
        val status = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) }.getOrNull()
        val charging = runCatching { bm?.isCharging == true }.getOrDefault(false) ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return PowerState(pct, charging)
    }
}

/** The on-device model may not run now (battery). No message: callers show a fixed reason, never exception text. */
class LocalModelPausedException : Exception(null as String?)

/** Wraps an on-device embedder with [LocalModelPower]: bulk only when allowed, one short query always. */
class PowerGatedEmbedder(private val inner: Embedder, private val power: () -> PowerState) : Embedder {
    override val fingerprint: String get() = inner.fingerprint

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (!LocalModelPower.bulkAllowed(power())) throw LocalModelPausedException()
        return inner.embedDocuments(texts)
    }

    override suspend fun embedQuery(text: String): FloatArray {
        if (!LocalModelPower.queryAllowed(power(), text)) throw LocalModelPausedException()
        return inner.embedQuery(text)
    }

    override val modalities: Set<Modality> get() = inner.modalities
    override val imageDims: Int? get() = inner.imageDims

    /** No G6 check (Ruling 18): live per-keyframe embedding is ordinary live work, gated by PhonePowerPolicy IMAGE_VECTORS; only backfill is battery-gated (by its caller). */
    override suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> = inner.embedImages(jpegs)

    override suspend fun embedQueryFull(text: String): FloatArray {
        if (!LocalModelPower.queryAllowed(power(), text)) throw LocalModelPausedException()
        return inner.embedQueryFull(text)
    }
}
