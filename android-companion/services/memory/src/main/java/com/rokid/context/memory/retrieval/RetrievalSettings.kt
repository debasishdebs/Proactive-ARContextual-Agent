package com.rokid.context.memory.retrieval

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RetrievalMode { LEXICAL, SEMANTIC, HYBRID }

/** Settings → Retrieval (spec §4.6). Defaults: hybrid, weighted 60/40, top-k 8, recency on with a 3-day half-life. */
data class RetrievalSettings(
    val mode: RetrievalMode = RetrievalMode.HYBRID,
    val fusion: FusionStrategy = FusionStrategy.WeightedScore(),
    val topK: Int = 8,
    val recencyBoost: Boolean = true,
    val halfLifeDays: Double = 3.0,
    val minConfidence: Double = 0.0,
) {
    fun clamped(): RetrievalSettings = copy(
        fusion = when (val f = fusion) {
            is FusionStrategy.WeightedScore -> FusionStrategy.WeightedScore(f.semanticWeight.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.6)
            is FusionStrategy.Rrf -> FusionStrategy.Rrf(f.k.coerceIn(1, 200))
            FusionStrategy.CombMnz -> FusionStrategy.CombMnz
        },
        topK = topK.coerceIn(1, 20),
        halfLifeDays = halfLifeDays.takeUnless { it.isNaN() }?.coerceIn(0.25, 30.0) ?: 3.0,
        minConfidence = minConfidence.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0,
    )
}

/** Persists [RetrievalSettings]; each fusion strategy's own config survives switching strategies. */
class RetrievalSettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<RetrievalSettings> = state.asStateFlow()

    fun update(transform: (RetrievalSettings) -> RetrievalSettings) {
        val next = transform(state.value).clamped()
        prefs.edit().apply {
            putString(K_MODE, next.mode.name)
            when (val f = next.fusion) {
                is FusionStrategy.WeightedScore -> { putString(K_FUSION, WEIGHTED); putString(K_WEIGHT, f.semanticWeight.toString()) }
                is FusionStrategy.Rrf -> { putString(K_FUSION, RRF); putInt(K_RRF_K, f.k) }
                FusionStrategy.CombMnz -> putString(K_FUSION, COMB_MNZ)
            }
            putInt(K_TOP_K, next.topK); putBoolean(K_RECENCY, next.recencyBoost)
            putString(K_HALF_LIFE, next.halfLifeDays.toString()); putString(K_MIN_CONF, next.minConfidence.toString())
        }.apply()
        state.value = next
    }

    /** The saved config of each strategy (for the fusion dropdown). */
    fun weightedConfig() = FusionStrategy.WeightedScore(double(K_WEIGHT, 0.6))
    fun rrfConfig() = FusionStrategy.Rrf(prefs.getInt(K_RRF_K, 60))

    private fun read(): RetrievalSettings = RetrievalSettings(
        mode = runCatching { RetrievalMode.valueOf(prefs.getString(K_MODE, null) ?: "HYBRID") }.getOrDefault(RetrievalMode.HYBRID),
        fusion = when (prefs.getString(K_FUSION, WEIGHTED)) { RRF -> rrfConfig(); COMB_MNZ -> FusionStrategy.CombMnz; else -> weightedConfig() },
        topK = prefs.getInt(K_TOP_K, 8), recencyBoost = prefs.getBoolean(K_RECENCY, true),
        halfLifeDays = double(K_HALF_LIFE, 3.0), minConfidence = double(K_MIN_CONF, 0.0),
    ).clamped()

    /** Doubles are stored as text so they read back exactly (a Float would turn 0.6 into 0.6000000238). */
    private fun double(key: String, default: Double): Double = prefs.getString(key, null)?.toDoubleOrNull() ?: default

    companion object {
        private const val K_MODE = "mode"; private const val K_FUSION = "fusion"; private const val K_WEIGHT = "weight"
        private const val K_RRF_K = "rrf_k"; private const val K_TOP_K = "top_k"; private const val K_RECENCY = "recency"
        private const val K_HALF_LIFE = "half_life_days"; private const val K_MIN_CONF = "min_confidence"
        private const val WEIGHTED = "weighted"; private const val RRF = "rrf"; private const val COMB_MNZ = "combmnz"
        @Volatile private var instance: RetrievalSettingsStore? = null

        fun get(context: Context): RetrievalSettingsStore = instance ?: synchronized(this) {
            instance ?: RetrievalSettingsStore(context.applicationContext.getSharedPreferences("retrieval_settings", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
