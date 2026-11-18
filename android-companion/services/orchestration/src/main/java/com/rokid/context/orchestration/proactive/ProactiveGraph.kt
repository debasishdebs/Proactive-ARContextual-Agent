package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.orchestration.AskZoneFence
import com.rokid.context.orchestration.BatterySaver
import com.rokid.context.orchestration.nudge.NudgeEnvironment
import com.rokid.context.orchestration.proactive.generators.PinInfo
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.provenance.ProvenanceGate

/** What only the phone app knows; every default fails closed (see [ProactiveSeams]). Set once at start-up (`ContextApp`). */
object ProactiveEnvironment {
    @Volatile var worn: () -> Boolean = { false }
    /** A vehicle episode is under way, or cannot be told. */
    @Volatile var driving: () -> Boolean = { true }
    /** The user's pause zones; null = unreadable (everything is blocked). */
    @Volatile var zones: () -> List<PauseZone>? = { null }
    /** The nudge cap ("Nudges per day"; 0 = off). */
    @Volatile var nudgeCap: () -> Int = { 0 }
}

/** Composition of the shadow loop: one store, one engine factory. Nothing here starts a thread or a timer. */
object ProactiveGraph {
    private const val TAG = "PcProactive"
    private const val ENC_PREFS = "proactive_shadow"
    @Volatile private var store: SuggestionStore? = null

    /**
     * The process store: the encrypted prefs file ([ENC_PREFS], app-private, backup-excluded, wiped by Delete all). If the Keystore cannot be opened the store stays in MEMORY for this process (nothing is
     * written in the clear), which fails safe: a restart then starts empty.
     */
    fun store(app: Context): SuggestionStore = store ?: synchronized(this) {
        store ?: run {
            val a = app.applicationContext
            val s = try {
                PrefsSuggestionStore(EncryptedSharedPreferences.create(
                    a, ENC_PREFS, MasterKey.Builder(a).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ))
            } catch (e: Exception) { PcLog.i(TAG, "shadow store not encrypted (${e.javaClass.simpleName}); memory only"); InMemorySuggestionStore() }
            s.also { store = it }
        }
    }

    fun pins(app: Context): List<UserPlaceView> = UserPinStore(app.applicationContext.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE)).let { st -> st.pins().map { UserPlaceView(it.id, it.name, it.note, it.lat, it.lon) } }

    class UserPlaceView(val id: String, val name: String, val note: String, val lat: Double, val lon: Double)

    fun history(app: Context) = RoomPlaceHistory(MemoryGraph.get(app).db) { pins(app).map { PinInfo(it.id, it.name, it.note) } }

    fun generator(app: Context) = PlaceRecallGenerator(history(app))

    fun seams(app: Context): ProactiveSeams {
        val a = app.applicationContext
        val mem = MemoryGraph.get(a)
        return ProactiveSeams(
            enabled = { ProactiveSettings.of(a).active() },
            paused = { mem.db.pauseDao().countCovering(System.currentTimeMillis()) > 0 || BatterySaver.isOn(a) },
            zoneGuard = AskZoneFence,
            worn = { ProactiveEnvironment.worn() },
            driving = { ProactiveEnvironment.driving() },
            hud = { NudgeEnvironment.hud?.invoke() },
            globalCap = { ProactiveEnvironment.nudgeCap() },
            globalToday = { since -> mem.db.nudgeGateDao().countSince(since) },
            anchorBlocked = { key -> anchorBlocked(a, key) },
            pinsLive = { ids -> val live = pins(a).map { it.id }.toSet(); ids.all { it in live } },
        )
    }

    /** True when the place is inside an enabled pause zone, or when where it is cannot be told (fail closed). Pins have their own position; a Wikidata place is read from the place pack. */
    internal suspend fun anchorBlocked(app: Context, key: String): Boolean {
        val zones = ProactiveEnvironment.zones() ?: return true
        if (zones.none { it.enabled }) return false
        val point: Pair<Double, Double> = (if (key.startsWith("user:")) pins(app).firstOrNull { it.id == key }?.let { it.lat to it.lon } else placeCoordinates(app, key)) ?: return true
        return ZoneEvaluator.blocksPoint(zones, point.first, point.second, 0f)
    }

    private suspend fun placeCoordinates(app: Context, key: String): Pair<Double, Double>? = try {
        com.rokid.context.work.frames.place.PlaceGraph.pack(app).placesByIds(listOf(key)).firstOrNull()?.let { it.lat to it.lon }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    fun engine(app: Context, store: SuggestionStore = store(app)): ProactiveEngine {
        val mem = MemoryGraph.get(app)
        return ProactiveEngine(mem.db, ProvenanceGate.forGraph(mem), store, listOf(generator(app)), seams(app))
    }

    fun replayer(app: Context): ProactiveReplayer {
        val mem = MemoryGraph.get(app)
        return ProactiveReplayer(mem.db, ProvenanceGate.forGraph(mem), generator(app), { seams(app) }, never = { store(app).never() })
    }

    fun liveness(app: Context) = SourceLiveness(ProvenanceGate.forGraph(MemoryGraph.get(app))) { runCatching { pins(app).map { it.id }.toSet() }.getOrNull() }

    /** The Forget / Delete all hook, registered once at start-up. */
    fun forgetListener(app: Context): ForgetListener {
        val a = app.applicationContext
        return ProactiveForgetListener({ store(a) }, { liveness(a) }, { ProactiveSettings.of(a).reset() })
    }

    internal fun resetForTests() { store = null }
}
