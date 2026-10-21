package com.rokid.context.core.model.context

data class FusionParams(
    val hintWalkTtlMs: Long = 90_000, val arTtlMs: Long = 1_800_000, val stepsTtlMs: Long = 90_000,
    val stepsWalkPerMin: Int = 60, val watchTtlMs: Long = 1_800_000, val fastKmh: Float = 25f,
    val fastTtlMs: Long = 120_000, val fastConfirmGapMs: Long = 45_000, val vehicleHoldMs: Long = 180_000, val imageMinConf: Float = 0.7f,
    val imageNewestMaxAgeMs: Long = 300_000, val imagePairMaxGapMs: Long = 180_000,
    val airplaneMinMs: Long = 120_000, val flightKmh: Float = 150f, val flightAnyKmh: Float = 350f,
    val flightWindowMs: Long = 600_000, val flightImageConf: Float = 0.6f, val hintStillTtlMs: Long = 120_000,
    val stillFrac: Float = 0.85f, val stillFracWithImage: Float = 0.70f, val intentWindowMs: Long = 600_000,
    val priorAa: Double = 0.85, val priorMyCar: Double = 0.75, val priorCarBt: Double = 0.50, val priorNone: Double = 0.15,
    val confLo: Double = 0.55, val confHi: Double = 0.95, val driverHi: Double = 0.6, val driverLo: Double = 0.4,
    val enterVehicleMs: Long = 30_000, val switchDriverMs: Long = 60_000, val enterFlightMs: Long = 120_000,
    val enterRestMs: Long = 120_000, val enterUnknownMs: Long = 60_000,
    /**
     * User ruling 2026-10-22: image intent never starts a vehicle episode, never lowers the REST threshold and never makes a FLIGHT
     * by itself (those reduce or change capture). false = intent is only a driver/passenger vote inside an episode that a
     * non-image fact started. true restores the old image-assisted behaviour (tests only).
     */
    val imageAssist: Boolean = false,
)

data class ContextDecision(
    val state: ContextState,
    val confidence: Float,
    val sinceMs: Long,
    val reasons: Set<ContextReason>,
    val pDriver: Double?,
    val candidate: ContextState,
    /** vehicleDet or vehicleImage or airplane on: the speed adapter and the intent sampler use it. */
    val vehicleSuspected: Boolean,
)

/**
 * Pure fusion of timestamped evidence into a coarse [ContextState] (spec 2026-10-21 §6). Not thread-safe.
 * Order: walking veto > flight > vehicle > rest > unknown. When unsure it says UNKNOWN (capture as configured).
 */
class ContextFusion(private val p: FusionParams = FusionParams()) {
    private var glasses: Evidence.GlassesMotion? = null
    private var activity: PhoneActivity? = null
    private var activityAt = 0L
    private var steps: Evidence.Steps? = null
    private val speeds = ArrayList<Evidence.Speed>()
    private val carSince = HashMap<CarLinkKind, Long>()      // connected kinds -> connect time
    private val btContradicted = HashSet<CarLinkKind>()
    private var airplaneSince: Long? = null
    private var watch: Evidence.WatchMotion? = null
    private var intents: List<Evidence.Intent> = emptyList()   // newest first, at most 3

    private var strongHeldAt: Long? = null
    private var btHeldAt: Long? = null
    private var lastNow = Long.MIN_VALUE

    private var committed = ContextState.UNKNOWN
    private var committedSince: Long? = null
    private var prevCandidate = ContextState.UNKNOWN
    private var candidateSince = 0L

    fun onEvidence(e: Evidence) {
        // Record, at the evidence time, whether a vehicle signal held just before this change (so an EXIT starts the hold).
        if (lastNow != Long.MIN_VALUE) refreshHeld(maxOf(e.atMs, lastNow))
        when (e) {
            is Evidence.GlassesMotion -> if (glasses == null || e.atMs >= glasses!!.atMs) glasses = e
            is Evidence.ActivityTransition ->
                // arrival order: the event stamp is informational, the engine's monotonic clock times expiry
                if (e.enter) { activity = e.activity; activityAt = arrival(e) }
                else if (activity == e.activity) { activity = null; activityAt = arrival(e) }
            is Evidence.Steps -> if (steps == null || e.atMs >= steps!!.atMs) steps = e
            is Evidence.Speed -> { speeds.add(e); pruneSpeeds(maxOf(e.atMs, lastNow)) }
            is Evidence.CarLink -> {
                if (e.connected) { if (e.kind !in carSince) carSince[e.kind] = arrival(e) }
                else { carSince.remove(e.kind); btContradicted.remove(e.kind) }
            }
            is Evidence.Airplane -> {
                if (e.on) { if (airplaneSince == null) airplaneSince = arrival(e) } else airplaneSince = null
            }
            is Evidence.WatchMotion -> if (watch == null || e.atMs >= watch!!.atMs) watch = e
            is Evidence.Intent -> intents = (intents + e).sortedByDescending { it.atMs }.take(3)
        }
    }

    /** Arrival time on the engine's monotonic clock: never earlier than the last evaluation. */
    private fun arrival(e: Evidence) = maxOf(e.atMs, lastNow)

    fun evaluate(nowMs: Long): ContextDecision {
        var now = nowMs
        if (lastNow != Long.MIN_VALUE && now < lastNow) {
            if (lastNow - now > CLOCK_JUMP_MS) reset() else now = lastNow
        }
        lastNow = now
        pruneSpeeds(now)
        if (committedSince == null) committedSince = now

        fun age(t: Long) = (now - t).coerceAtLeast(0)
        val reasons = LinkedHashSet<ContextReason>()

        // --- walking
        val g = glasses
        val hintWalk = g != null && g.state == com.rokid.context.core.model.MotionState.WALKING && age(g.atMs) <= p.hintWalkTtlMs
        val act = activity
        val actFresh = act != null && age(activityAt) <= p.arTtlMs
        val phoneWalk = actFresh && (act == PhoneActivity.WALKING || act == PhoneActivity.RUNNING)
        val st = steps
        val stepsWalk = st != null && age(st.atMs) <= p.stepsTtlMs && st.stepsPerMin >= p.stepsWalkPerMin
        val w = watch
        val watchFresh = w != null && age(w.atMs) <= p.watchTtlMs
        val watchWalk = watchFresh && (w!!.state == WatchState.WALKING || w.state == WatchState.RUNNING)
        val walking = hintWalk || phoneWalk || stepsWalk || watchWalk
        val fast = fastConfirmed(now)
        val walkingVeto = walking && !fast

        // --- vehicle
        val aa = CarLinkKind.ANDROID_AUTO in carSince
        val arVehicle = actFresh && act == PhoneActivity.IN_VEHICLE
        val watchVehicle = watchFresh && w!!.state == WatchState.IN_VEHICLE
        val strongLive = aa || arVehicle || fast || watchVehicle
        if (strongLive) strongHeldAt = now
        // a held-only fact only bridges a stop while already in a vehicle state; it never starts an episode
        val strong = strongLive || (committed.isVehicle() && heldWithin(strongHeldAt, now))

        // two newest intents consistent? (newest fresh, close together, confident)
        val i0 = intents.getOrNull(0)
        val i1 = intents.getOrNull(1)
        val pairOk = i0 != null && i1 != null && age(i0.atMs) <= p.imageNewestMaxAgeMs &&
            (i0.atMs - i1.atMs) <= p.imagePairMaxGapMs && i0.confidence >= p.imageMinConf && i1.confidence >= p.imageMinConf
        // Image intent alone never starts a vehicle episode (user ruling 2026-10-22): only with the explicit imageAssist flag.
        val vehicleImage = p.imageAssist && pairOk && i0!!.label.isVehicleLabel() && i1!!.label.isVehicleLabel()

        // car BT contradicted by two indoor/non-vehicle images (latched until that link disconnects)
        if (pairOk && !strong && i0!!.label.isNonVehicleLabel() && i1!!.label.isNonVehicleLabel()) {
            for (k in listOf(CarLinkKind.MY_CAR_BT, CarLinkKind.CAR_CLASS_BT)) {
                val since = carSince[k] ?: continue
                if (k !in btContradicted && i1.atMs >= since) { btContradicted.add(k); btHeldAt = null }
            }
        }
        val myCar = CarLinkKind.MY_CAR_BT in carSince && CarLinkKind.MY_CAR_BT !in btContradicted
        val carBt = CarLinkKind.CAR_CLASS_BT in carSince && CarLinkKind.CAR_CLASS_BT !in btContradicted
        val btLive = myCar || carBt
        if (btLive) btHeldAt = now
        val bt = btLive || (committed.isVehicle() && heldWithin(btHeldAt, now))
        val vehicleDet = strong || bt
        val vehicleLive = strongLive || btLive || vehicleImage

        // --- flight
        val airplaneOnMs = airplaneSince?.let { age(it) }
        val airplaneLong = airplaneOnMs != null && airplaneOnMs >= p.airplaneMinMs
        val speedFlight = speeds.any { it.kmh >= p.flightKmh }
        val speedAny = speeds.any { it.kmh >= p.flightAnyKmh }
        val newest = i0?.takeIf { age(it.atMs) <= p.intentWindowMs }
        val cabin = p.imageAssist && newest != null && newest.label == IntentLabel.FLIGHT_CABIN && newest.confidence >= p.flightImageConf
        val flight = (airplaneLong && (speedFlight || cabin)) || speedAny

        // --- rest
        val idleImage = p.imageAssist && newest != null && newest.confidence >= p.imageMinConf &&
            (newest.label == IntentLabel.SITTING_IDLE || newest.label == IntentLabel.COMPUTER_WORK)
        val hintFresh = g != null && age(g.atMs) <= p.hintStillTtlMs
        val fracOk = hintFresh && (g!!.stillFrac5m >= p.stillFrac || (idleImage && g.stillFrac5m >= p.stillFracWithImage))
        val moving = actFresh && (act == PhoneActivity.WALKING || act == PhoneActivity.RUNNING || act == PhoneActivity.ON_BICYCLE)
        // a watch ASLEEP/REST is ignored while a FRESH glasses hint says the wearer is moving (stale ASLEEP, e.g. watch off the wrist)
        val glassesMoving = hintFresh && g!!.stillFrac5m in 0f..<p.stillFrac   // negative = unknown fraction: says nothing
        val asleep = watchFresh && w!!.state == WatchState.ASLEEP && !glassesMoving
        val still = (fracOk && !moving && newest?.label != IntentLabel.WORKOUT) || asleep

        // --- driver probability (only meaningful with a vehicle fact)
        val prior = when {
            aa -> p.priorAa
            myCar -> p.priorMyCar
            carBt -> p.priorCarBt
            else -> p.priorNone
        }
        var l = logit(prior)
        var sawDriver = false
        var sawPass = false
        for (i in intents) {
            if (age(i.atMs) > p.intentWindowMs) continue
            val s = when (i.label) { IntentLabel.DRIVING -> { sawDriver = true; 1 }; IntentLabel.VEHICLE_PASSENGER -> { sawPass = true; -1 }; else -> 0 }
            if (s != 0) l += s * logit(i.confidence.toDouble().coerceIn(p.confLo, p.confHi))
        }
        val pDriver = sigmoid(l)
        val vehicleFact = vehicleDet || vehicleImage

        // --- candidate
        val committedVehicle = committed.isVehicle()
        val candidate = when {
            walkingVeto -> ContextState.WALKING
            flight -> ContextState.FLIGHT
            vehicleFact -> when {
                !vehicleLive && committedVehicle -> committed   // only held: a stop does not change the sub-state
                pDriver >= p.driverHi -> ContextState.VEHICLE_DRIVING
                pDriver <= p.driverLo -> ContextState.VEHICLE_PASSENGER
                committedVehicle -> committed
                pDriver >= 0.5 -> ContextState.VEHICLE_DRIVING
                else -> ContextState.VEHICLE_PASSENGER
            }
            still -> ContextState.REST
            else -> ContextState.UNKNOWN
        }

        // --- hysteresis
        if (candidate == committed) {
            prevCandidate = candidate; candidateSince = now
        } else {
            // a driver/passenger flip inside one entering episode keeps the 30 s clock
            val sameEpisode = candidate.isVehicle() && prevCandidate.isVehicle() && !committedVehicle
            if (candidate != prevCandidate && !sameEpisode) candidateSince = now
            prevCandidate = candidate
            if (now - candidateSince >= dwell(committed, candidate)) {
                committed = candidate; committedSince = now
                prevCandidate = candidate; candidateSince = now
            }
        }

        // --- reasons
        if (hintWalk) reasons += ContextReason.GLASSES_WALK
        if (phoneWalk) reasons += ContextReason.PHONE_WALK
        if (stepsWalk) reasons += ContextReason.STEPS
        if (watchWalk) reasons += ContextReason.WATCH_WALK
        if (fast) reasons += ContextReason.SPEED
        if (aa) reasons += ContextReason.ANDROID_AUTO
        if (myCar) reasons += ContextReason.MY_CAR_BT
        if (carBt) reasons += ContextReason.CAR_BT
        if (arVehicle) reasons += ContextReason.AR_VEHICLE
        if (watchVehicle) reasons += ContextReason.WATCH_VEHICLE
        if (vehicleImage) reasons += ContextReason.IMAGE_VEHICLE
        if (vehicleFact && sawDriver) reasons += ContextReason.IMAGE_DRIVER
        if (vehicleFact && sawPass) reasons += ContextReason.IMAGE_PASSENGER
        if (btContradicted.isNotEmpty()) reasons += ContextReason.BT_CONTRADICTED
        if (airplaneSince != null) reasons += ContextReason.AIRPLANE
        if (flight && cabin) reasons += ContextReason.IMAGE_FLIGHT
        if (still && fracOk) reasons += ContextReason.GLASSES_STILL
        if (still && fracOk && idleImage) reasons += ContextReason.IMAGE_IDLE
        if (asleep) reasons += ContextReason.WATCH_ASLEEP

        val confidence = when (committed) {
            ContextState.WALKING, ContextState.FLIGHT -> 0.9f
            ContextState.VEHICLE_DRIVING, ContextState.VEHICLE_PASSENGER -> {
                val mult = when { strong -> 0.95; bt -> 0.8; else -> 0.75 }
                (maxOf(pDriver, 1 - pDriver) * mult).toFloat()
            }
            ContextState.REST -> glasses?.stillFrac5m ?: 0.8f
            ContextState.UNKNOWN -> 0f
        }
        return ContextDecision(
            state = committed, confidence = confidence, sinceMs = committedSince ?: now, reasons = reasons,
            pDriver = if (vehicleFact) pDriver else null, candidate = candidate,
            vehicleSuspected = vehicleDet || vehicleImage || airplaneSince != null,
        )
    }

    /** Dwell before [from] may change to [to]; WALKING is immediate, leaving WALKING takes at least [FusionParams.enterUnknownMs]. */
    private fun dwell(from: ContextState, to: ContextState): Long {
        val base = when (to) {
            ContextState.WALKING -> 0L
            ContextState.VEHICLE_DRIVING, ContextState.VEHICLE_PASSENGER -> if (from.isVehicle()) p.switchDriverMs else p.enterVehicleMs
            ContextState.FLIGHT -> p.enterFlightMs
            ContextState.REST -> p.enterRestMs
            ContextState.UNKNOWN -> p.enterUnknownMs
        }
        return if (from == ContextState.WALKING) maxOf(base, p.enterUnknownMs) else base
    }

    private fun refreshHeld(at: Long) {
        // facts from the stored evidence at time [at], before the incoming evidence changes them
        fun age(t: Long) = (at - t).coerceAtLeast(0)
        val act = activity
        val actFresh = act != null && age(activityAt) <= p.arTtlMs
        val w = watch
        val watchVeh = w != null && age(w.atMs) <= p.watchTtlMs && w.state == WatchState.IN_VEHICLE
        val fast = fastConfirmed(at)
        if (CarLinkKind.ANDROID_AUTO in carSince || (actFresh && act == PhoneActivity.IN_VEHICLE) || watchVeh || fast) strongHeldAt = at
        if ((CarLinkKind.MY_CAR_BT in carSince && CarLinkKind.MY_CAR_BT !in btContradicted) ||
            (CarLinkKind.CAR_CLASS_BT in carSince && CarLinkKind.CAR_CLASS_BT !in btContradicted)) btHeldAt = at
    }

    /**
     * Two CONSECUTIVE readings >= fastKmh at most fastConfirmGapMs apart (the source samples every 30 s), both fresh
     * (<= fastTtlMs). A slower reading in between resets the pair, so one spike never counts.
     */
    private fun fastConfirmed(now: Long): Boolean {
        val fresh = speeds.filter { (now - it.atMs).coerceAtLeast(0) <= p.fastTtlMs }.sortedBy { it.atMs }
        return fresh.zipWithNext().any { (a, b) ->
            a.kmh >= p.fastKmh && b.kmh >= p.fastKmh && b.atMs - a.atMs <= p.fastConfirmGapMs
        }
    }

    private fun heldWithin(t: Long?, now: Long) = t != null && (now - t).coerceAtLeast(0) <= p.vehicleHoldMs

    private fun pruneSpeeds(now: Long) {
        speeds.removeAll { now - it.atMs > p.flightWindowMs }
        while (speeds.size > MAX_SPEEDS) speeds.removeAt(0)
    }

    /** A large backwards clock jump: stale evidence cannot be aged correctly, so forget it (UNKNOWN = today's behaviour). */
    private fun reset() {
        glasses = null; activity = null; activityAt = 0L; steps = null; speeds.clear(); carSince.clear()
        btContradicted.clear(); airplaneSince = null; watch = null; intents = emptyList()
        strongHeldAt = null; btHeldAt = null
        committed = ContextState.UNKNOWN; committedSince = null; prevCandidate = ContextState.UNKNOWN; candidateSince = 0L
    }

    private fun ContextState.isVehicle() = this == ContextState.VEHICLE_DRIVING || this == ContextState.VEHICLE_PASSENGER
    private fun IntentLabel.isVehicleLabel() = this == IntentLabel.DRIVING || this == IntentLabel.VEHICLE_PASSENGER
    private fun IntentLabel.isNonVehicleLabel() =
        this == IntentLabel.SITTING_IDLE || this == IntentLabel.COMPUTER_WORK || this == IntentLabel.WORKOUT || this == IntentLabel.WALKING

    private fun logit(x: Double) = Math.log(x / (1 - x))
    private fun sigmoid(x: Double) = 1.0 / (1.0 + Math.exp(-x))

    private companion object {
        const val CLOCK_JUMP_MS = 60_000L
        const val MAX_SPEEDS = 32
    }
}
