package com.rokid.context.phone.context

import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextFusion
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.FusionParams
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.core.model.context.WatchMotionRing
import com.rokid.context.core.model.context.WatchSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's context hub (spec §3-§6): reconciles the opt-in sources against [settings] and [registry], fuses their evidence
 * (plus the glasses' motion hint) into a [ContextState] and publishes it as [message] for `pc_motion_ctx`.
 *
 * One serial executor: evidence intake, reconcile and the 10 s tick are commands on a single channel read by one coroutine on
 * [scope]; callers on other threads only enqueue. [ContextFusion] is touched only there.
 */
class ContextHub(
    private val scope: CoroutineScope,
    private val settings: ContextSourceSettings,
    private val registry: ContextSourceRegistry,
    private val params: FusionParams = FusionParams(watchTtlMs = 1_500_000),
    private var fusion: ContextFusion = ContextFusion(params),
    /** v0.9.7 B: the user's changed fusion thresholds (non-default keys of [com.rokid.context.capture.plan.FusionSettings]); applied over [params] on this hub's actor, no restart. Null = the fixed [params]. */
    private val overrides: StateFlow<Map<String, Double>>? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    val stats: ContextHubStats = ContextHubStats(clock),
    private val log: (String) -> Unit = {},
    /** Called (on the hub's actor, must not block) after the watch source was switched off and its ring emptied: whatever else holds the values it gave must drop them too. */
    private val onWatchOff: () -> Unit = {},
    /** v3.0 T14: every evidence this hub accepted, and every evaluation (the hub is alive). Must not block; a throw is swallowed. */
    private val onAccepted: (Evidence) -> Unit = {},
    private val onEvaluated: (ContextDecision) -> Unit = {},
) : EvidenceSink {
    private sealed interface Cmd {
        class Ev(val e: Evidence, val sourceId: String?, val gen: Int) : Cmd
        data object Reconcile : Cmd
        class Fail(val id: String, val gen: Int) : Cmd
        class Params(val overrides: Map<String, Double>) : Cmd
        data object Drain : Cmd
        data object Tick : Cmd
        data object Stop : Cmd
    }

    private val inbox = Channel<Cmd>(Channel.UNLIMITED)
    /** The params the fusion runs with: [params] plus the user's overrides (actor-only after construction). */
    private var effective: FusionParams = params
    private var appliedHash = "default"
    private val lock = Any()
    private val evq = ArrayDeque<Cmd.Ev>()
    private val drainSignalled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var started = false
    private var actor: Job? = null
    private var feeders: List<Job> = emptyList()

    private val _message = MutableStateFlow<MotionContextMessage?>(null)
    /** Null while no source is running; the Hub sends every non-null value on `pc_motion_ctx`. */
    val message: StateFlow<MotionContextMessage?> = _message
    private val _decision = MutableStateFlow(ContextDecision(ContextState.UNKNOWN, 0f, clock(), emptySet(), null, ContextState.UNKNOWN, false))
    val decision: StateFlow<ContextDecision> = _decision
    private val _status = MutableStateFlow(ALL_IDS.associateWith { SourceStatus.OFF })
    val status: StateFlow<Map<String, SourceStatus>> = _status

    @Volatile var speedWanted: Boolean = false
        private set

    /** id -> generation of the running instance; written on the actor, read by the sink wrappers. */
    private val live = ConcurrentHashMap<String, Int>()

    // --- actor-only state
    private val running = HashMap<String, Int>()
    private val failed = HashSet<String>()
    private var nextGen = 0
    private val retained = LinkedHashMap<String, Evidence>()

    /** The recent watch values this hub accepted (memory only, bounded): what a canvas `src.watch_motion` run reads. Emptied when the watch source is switched off. */
    val watchRing = WatchMotionRing()
    private var retainSeq = 0
    private var lastEvalAt = 0L
    private var committed = ContextState.UNKNOWN
    private var lastStatsLogAt = 0L
    private var lastSpeedCall: Boolean? = null
    private var lastSpeedObj: SpeedControl? = null

    /** Idempotent. Also called again after [stop]: the sources are then started once more. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            val prev = actor
            actor = scope.launch { prev?.join(); runActor() }
            val now = clock()
            feeders = listOf(
                scope.launch { settings.changes.collect { inbox.trySend(Cmd.Reconcile) } },
                scope.launch { registry.changes.collect { inbox.trySend(Cmd.Reconcile) } },
                scope.launch { overrides?.collect { inbox.trySend(Cmd.Params(it)) } },
                scope.launch { while (true) { delay(TICK_MS); inbox.trySend(Cmd.Tick) } },
            )
            lastEvalAt = now; lastStatsLogAt = now
        }
    }

    /** Idempotent: stops every running source and the loop; the revoke and a null [message] follow on the executor. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            feeders.forEach { it.cancel() }; feeders = emptyList()
            inbox.trySend(Cmd.Stop)
        }
    }

    /** The shared sink: any thread. Evidence of a source that is not running is dropped on the executor. */
    override fun accept(e: Evidence) { if (started) offer(Cmd.Ev(e, null, 0)) }

    /** Evidence waits in a bounded queue (drop-oldest, counted); the actor drains it on a [Cmd.Drain] signal. */
    private fun offer(c: Cmd.Ev) {
        var dropped = false
        synchronized(evq) { evq.addLast(c); if (evq.size > MAX_PENDING) { evq.removeFirst(); dropped = true } }
        if (dropped) stats.onDropped()
        if (drainSignalled.compareAndSet(false, true)) inbox.trySend(Cmd.Drain)
    }

    /**
     * The sink for one run of source [id]: a source calls this **inside its `start()`** and uses the result for that run only.
     * The generation is captured here, so after a stop (and a restart) the old run's sink is dropped. Taken while the source is
     * not being started or running, the sink is a no-op. [accept] is for the glasses hint and tests (kind-based check only).
     */
    fun sinkFor(id: String): EvidenceSink {
        val gen = live[id] ?: return EvidenceSink { }
        return EvidenceSink { e -> if (started) offer(Cmd.Ev(e, id, gen)) }
    }

    /**
     * For a source whose request fails asynchronously (after `start()` returned true): call the result to mark that run FAILED
     * (the source is stopped, and a settings toggle starts it again). Take it inside `start()` like [sinkFor]; a stale run's call is ignored.
     */
    fun failFor(id: String): () -> Unit {
        val gen = live[id] ?: return { }
        return { if (started) inbox.trySend(Cmd.Fail(id, gen)) }
    }

    /** New thresholds: a fresh engine on the retained evidence (hysteresis restarts from UNKNOWN, like a source change); nothing happens when the effective values did not change. */
    private fun onParams(o: Map<String, Double>) {
        val h = com.rokid.context.capture.plan.FusionSettings.hash(o)
        if (h == appliedHash) return
        appliedHash = h; effective = com.rokid.context.capture.plan.FusionSettings.apply(params, o)
        log("fusion settings applied")
        rebuild(); publish(clock())
    }

    private fun onSourceFailed(c: Cmd.Fail) {
        if (running[c.id] != c.gen) return
        stopSource(c.id, registry.get(c.id)); failed.add(c.id)
        _status.value = _status.value + (c.id to SourceStatus.FAILED)
        rebuild(); publish(clock())
    }

    fun onMotionHint(h: MotionHint) { GlassesClassifierStatus.report(h.classifierHash); accept(Evidence.GlassesMotion(clock(), h.state, h.stillFrac5m)) }

    private suspend fun runActor() {
        for (c in inbox) {
            try {
                when (c) {
                    is Cmd.Ev -> onEvidence(c)
                    Cmd.Drain -> drain()
                    Cmd.Reconcile -> { reconcile(); publish(clock()) }
                    is Cmd.Fail -> onSourceFailed(c)
                    is Cmd.Params -> onParams(c.overrides)
                    Cmd.Tick -> onTick()
                    Cmd.Stop -> { shutdown(); return }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { log("ctx hub ${c::class.simpleName} failed: ${e.javaClass.simpleName}") }
        }
    }

    private fun drain() {
        drainSignalled.set(false)
        while (true) {
            val c = synchronized(evq) { evq.removeFirstOrNull() } ?: return
            try { onEvidence(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ctx hub evidence failed: ${e.javaClass.simpleName}") }
        }
    }

    private fun onEvidence(c: Cmd.Ev) {
        val src = c.sourceId ?: sourceOf(c.e)
        if (src != null && running[src] == null) return
        if (c.sourceId != null && running[c.sourceId] != c.gen) return
        fusion.onEvidence(c.e)
        safe(Unit) { onAccepted(c.e) }
        (c.e as? Evidence.WatchMotion)?.let { watchRing.add(WatchSample(it.atMs, it.state, it.confidence)) }
        retain(c.e)
        stats.count(c.e)
        publish(clock())
    }

    private fun onTick() {
        val now = clock()
        publish(now)
        if (speedWanted && registry.speed?.requesting == true) stats.addLocOnSeconds(TICK_MS / 1000)
        if (now - lastStatsLogAt >= STATS_LOG_MS) { lastStatsLogAt = now; log(stats.line(_decision.value.state)) }
    }

    // --- reconcile
    private fun wanted(id: String) =
        if (id == AIRPLANE) settings.enabled("phone_activity") || settings.enabled("phone_speed") else settings.enabled(id)

    private fun reconcile() {
        var revoked = false
        val next = HashMap<String, SourceStatus>()
        for (id in ALL_IDS) {
            val s = registry.get(id)
            next[id] = when {
                !wanted(id) -> {
                    failed.remove(id)
                    if (running.containsKey(id)) { stopSource(id, s); revoked = true }
                    SourceStatus.OFF
                }
                running.containsKey(id) -> SourceStatus.ON
                id in failed -> SourceStatus.FAILED
                s == null || !safe(false) { s.available() } -> SourceStatus.UNAVAILABLE
                else -> {
                    // The generation exists before start(): a sink taken inside start() carries it, and evidence sent during start() is accepted.
                    running[id] = ++nextGen; live[id] = nextGen
                    if (safe(false) { s.start() }) SourceStatus.ON
                    else { running.remove(id); live.remove(id); failed.add(id); SourceStatus.FAILED }
                }
            }
        }
        _status.value = next
        if (revoked) rebuild()
    }

    private inline fun <T> safe(fallback: T, f: () -> T): T = try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

    /** Marks the source revoked (late callbacks are dropped) and forgets its evidence; the caller rebuilds the fusion. */
    private fun stopSource(id: String, s: ContextSource?) {
        running.remove(id); live.remove(id)
        if (id == "watch_app") { watchRing.clear(); safe(Unit) { onWatchOff() } } // a source switched off forgets what it gave
        safe(Unit) { s?.stop() }
        retained.keys.removeAll { retained[it]?.let { e -> sourceOf(e) } == id }
    }

    /** Task 2's engine has no "forget": a fresh engine replays the retained evidence of the sources still on (hysteresis restarts from UNKNOWN). */
    private fun rebuild() {
        fusion = ContextFusion(effective)
        committed = ContextState.UNKNOWN
        retained.values.forEach { fusion.onEvidence(it) }
        forcePublish = true
    }
    private var forcePublish = false

    private fun retain(e: Evidence) {
        val key = when (e) {
            is Evidence.GlassesMotion -> "g"
            is Evidence.ActivityTransition -> "act"
            is Evidence.Steps -> "steps"
            is Evidence.CarLink -> "car:${e.kind}"
            is Evidence.Airplane -> "air"
            is Evidence.WatchMotion -> "watch"
            is Evidence.Speed -> "speed:${retainSeq++}"
            is Evidence.Intent -> "intent:${retainSeq++}"
        }
        retained.remove(key); retained[key] = e
        trim("speed:", MAX_SPEEDS); trim("intent:", MAX_INTENTS)
    }

    private fun trim(prefix: String, max: Int) {
        val keys = retained.keys.filter { it.startsWith(prefix) }
        keys.dropLast(max).forEach { retained.remove(it) }
    }

    // --- evaluate and publish
    private fun publish(now: Long) {
        val d = fusion.evaluate(now)
        stats.addStateTime(_decision.value.state, now - lastEvalAt); lastEvalAt = now
        if (d.state != committed) { committed = d.state; stats.onChange() }
        _decision.value = d
        safe(Unit) { onEvaluated(d) }
        applySpeed(settings.enabled("phone_speed") && d.vehicleSuspected)

        val force = forcePublish; forcePublish = false
        val cur = _message.value
        if (SIX_IDS.none { running.containsKey(it) }) {
            if (cur != null) revokeThenNull(now)
            return
        }
        val apply = settings.applyToCapture
        val next = MotionContextMessage(
            state = d.state, confidence = d.confidence.coerceIn(0f, 1f), sinceMs = d.sinceMs, atMs = now, apply = apply,
            reasons = d.reasons.map { it.name },
        )
        val send = force || cur == null || cur.state != next.state || cur.apply != apply ||
            (next.state != ContextState.UNKNOWN && now - cur.atMs >= RESEND_MS)
        if (!send) return
        if (cur != null && cur.apply != apply && cur.state != ContextState.UNKNOWN) { _message.value = revoke(now, apply); stats.onSend() }
        _message.value = next; stats.onSend()
    }

    private fun revoke(now: Long, apply: Boolean) =
        MotionContextMessage(state = ContextState.UNKNOWN, confidence = 0f, sinceMs = now, atMs = now, apply = apply, reasons = emptyList())

    private fun revokeThenNull(now: Long) {
        _message.value = revoke(now, settings.applyToCapture); stats.onSend()
        _message.value = null
    }

    private fun applySpeed(want: Boolean) {
        speedWanted = want
        val sc = registry.speed
        if (want == lastSpeedCall && sc === lastSpeedObj) return
        lastSpeedCall = want; lastSpeedObj = sc
        safe(Unit) { sc?.setWanted(want) }
    }

    private fun shutdown() {
        for (id in running.keys.toList()) stopSource(id, registry.get(id))
        failed.clear(); synchronized(evq) { evq.clear() }
        _status.value = ALL_IDS.associateWith { SourceStatus.OFF }
        fusion = ContextFusion(effective); committed = ContextState.UNKNOWN; retained.clear()
        applySpeed(false)
        if (_message.value != null) revokeThenNull(clock())
        _decision.value = _decision.value.copy(state = ContextState.UNKNOWN, reasons = emptySet(), vehicleSuspected = false)
    }

    companion object {
        const val AIRPLANE = "phone_airplane"
        val SIX_IDS = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "watch_app", "image_intent")
        private val ALL_IDS = SIX_IDS + AIRPLANE
        const val TICK_MS = 10_000L
        const val RESEND_MS = 60_000L
        const val STATS_LOG_MS = 600_000L
        /** Evidence waiting for the executor; beyond this the oldest is dropped and counted (`dropped` in the stats snapshot). */
        const val MAX_PENDING = 1024
        private const val MAX_SPEEDS = 8
        private const val MAX_INTENTS = 3

        /** Which source produces an evidence kind; null = the glasses hint (always on, never revoked). */
        fun sourceOf(e: Evidence): String? = when (e) {
            is Evidence.GlassesMotion -> null
            is Evidence.ActivityTransition -> "phone_activity"
            is Evidence.Steps -> "phone_steps"
            is Evidence.Speed -> "phone_speed"
            is Evidence.CarLink -> "phone_car"
            is Evidence.Airplane -> AIRPLANE
            is Evidence.WatchMotion -> "watch_app"
            is Evidence.Intent -> "image_intent"
        }
    }
}
