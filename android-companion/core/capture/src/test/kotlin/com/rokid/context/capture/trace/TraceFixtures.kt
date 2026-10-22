package com.rokid.context.capture.trace

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

object TraceFixtures {
    const val ID = "trace-0001-golden"

    /** One of every event type, in time order (the golden chunk). A valid whole trace: initial state at the start (1000), pre-roll end at 11,000, close at 12,000. */
    val events: List<TraceEvent> = listOf(
        TraceEvent.Ctx(1000, ContextState.REST),
        TraceEvent.Resting(1000, true),
        TraceEvent.Profile(1000, CameraWake.SETTLE, 10_000, 30_000, null, 60_000, 333, BudgetTier.T0, lowPower = false, heatSlow = true, quickLookMs = 3000, ctxCap = 3, hard = true),
        TraceEvent.GateParams(1000, 10, 24, true),
        TraceEvent.Plan(1000, 7, "abc123def456", PlanMode.SHADOW),
        TraceEvent.Gyro(1000, 990, 1.0f, -1.0f, 0.5f),
        TraceEvent.Accel(1004, 996, 0f, 0f, 9.80665f),     // batched delivery: sensor time is before the append time and out of order against the gyro
        TraceEvent.Episode(2000, true, EpisodeCause.TIMER),
        TraceEvent.Score(2500, 42, 0x0123456789abcdefL, -0x0123456789abcdf0L, 12.5f, true, MotionState.STILL, dwellArmed = false, force = false, cap = 4, hard = false, nowMs = 2490),
        TraceEvent.Gate(2600, 42, GateOutcome.KEEP, "", encoded = false),
        TraceEvent.Flush(2700, "window"),
        TraceEvent.Backoff(2800, 2),
        TraceEvent.Blocked(2900, null),
        TraceEvent.DarkStreak(2950, 1),
        TraceEvent.Episode(3000, false, EpisodeCause.DWELL, firstFrameAtMs = 2900, openedAtMs = 2100, outcome = EpisodeOutcome.OK),
        TraceEvent.PreRollEnd(11_000),
        TraceEvent.Close(12_000, "stop"),
    )

    val goldenPayload = """["PCT1","trace-0001-golden",0,17]
["cx",1000,"REST"]
["rs",1000,true]
["pf",1000,"SETTLE",10000,30000,null,60000,333,"T0",false,true,3000,3,true]
["gp",1000,10,24,true]
["pl",1000,7,"abc123def456","SHADOW"]
["g",1000,990,1065353216,-1082130432,1056964608]
["a",1004,996,0,0,1092413450]
["ep",2000,true,"timer",null,null,null]
["sc",2500,42,"0123456789abcdef","fedcba9876543210",1095237632,true,"STILL",false,false,4,false,2490]
["gt",2600,42,"keep","",false]
["fl",2700,"window"]
["bo",2800,2]
["bl",2900,null]
["dk",2950,1]
["ep",3000,false,"dwell",2900,2100,"ok"]
["pr",11000]
["cl",12000,"stop"]
"""
    const val GOLDEN_RAW_BYTES = 599
    const val GOLDEN_SHA = "946d13e6c32110f73eb37e8b9a39e85f836eb4384569a274f6d62e43ce655086"
    /** A gzip of [goldenPayload] made by a different implementation (python gzip, mtime 0). */
    const val GOLDEN_B64 = "H4sIAAAAAAAC/0VSTWuDQBC992fMeQL7rXssbaCFQEsVeig9GF0lxEZRQ/rzO7Ou6WVl37438+aNX/D+VEpAWKaqDjshhNx1Q9+EC6BAmX0/fEH9CyjpBeFjX5TA0DQnaJmugYGx3TjFviwP+/UmUMfzcu17dCugNUIpANuqn0PURxLqe61uTLWkQGX+W/QJzhCqYy2VbkJrrCPzxcvj89tndNYlkvesd1ZbraTDnRS5kloYrQi2zjvjRM6CKgoMCRxNzCKvjNTGCn4N5EVtg1JKp58wwTrP/WDeXBPPEs8oBEHeyFiWe/JJJslhG5r6WPk8c5YsSMFNvVU6c2RorV2Ur4fDlst6mvRVxkc33UJdXOpyDmwOkoKfW0pIZbyE2+nSDLcYyHEgMCdQxRtTfFoJA82ZAUtzb+PGbax9obmFflOQa6o8nGPVkVKQHHT8QXgzMSWYl2Ekwh/6dpsNVwIAAA=="
    const val GOLDEN_TRACE_SHA = "132a333aeac7cf63fc341b074671ec6fb98cfeafe9f9351d5729b221fb1cb549"

    /** Explicit values (M8): a retune of the GlanceParams defaults must not break the frozen golden. */
    val glance = GlanceParams(
        onsetRadS = 0.6f, releaseRadS = 0.3f, bridgeMs = 250, minBoutMs = 300, minBoutRad = 0.26f, settleMs = 1_500, maxDtMs = 100,
        deadbandRadS = 0.05f, noveltyWindowMs = 20_000, noveltyMinRad = 0.30f, bigTurnRad = 0.8f, capPerMin = 4, restCapPerMin = 2,
    )

    val snapshot = StartSnapshot(
        settingsHash = "a1b2c3d4e5f6", ring = listOf(RingHash(0x0123456789abcdefL, -0x0123456789abcdf0L)), lastKeyframeAt = 500, keptAt = listOf(100, 200),
        gateLastKeptAt = 480, gateKeptAt = listOf(100, 480), lastBriefAt = 450, stillStep = 2, lastCheckHash = RingHash(0x1111222233334444L, 0x5555666677778888L),
        darkStreak = 1, bindBackoffUntil = 900, coldStartGrace = false, startups = listOf(300, 420, 510),
        pose = PoseSnapshot(0.5f, -0.25f, 0f, true, 0.1f, 0f, -0.1f, 800), lastPhotoAttemptAtMs = 250,
        planGen = 7, planHash = "abc123def456", glance = glance, ctx = ContextState.REST, resting = true, tier = BudgetTier.T0, sampleEvery = 1, exact = true,
    )

    fun manifest(
        chunkCount: Int = 1, eventCount: Long = 17, rawBytes: Long = GOLDEN_RAW_BYTES.toLong(), traceSha: String = GOLDEN_TRACE_SHA,
        recorded: TraceStatus = TraceStatus.COMPLETE, snap: StartSnapshot = snapshot, id: String = ID,
    ) = Manifest(
        traceId = id, installId = "install-abc", deletionCounter = 3, revision = 1, parentTraceId = null, bootId = "42",
        startElapsedMs = 1000, endElapsedMs = 12_000, startWallMs = 1_700_000_001_000, endWallMs = 1_700_000_012_010,
        startPhoneWallMs = 1_700_000_001_250, endPhoneWallMs = 1_700_000_012_262, phoneOffsetMs = 250, offsetUncertaintyMs = 40,
        chunkCount = chunkCount, eventCount = eventCount, rawBytes = rawBytes, traceSha256 = traceSha, recorded = recorded, closeReason = "stop", snapshot = snap,
    )

    val goldenManifest = """{"bootId":"42","chunkCount":1,"closeReason":"stop","deletionCounter":3,"endElapsedMs":12000,"endPhoneWallMs":1700000012262,"endWallMs":1700000012010,"eventCount":17,"installId":"install-abc","offsetUncertaintyMs":40,"parentTraceId":null,"phoneOffsetMs":250,"rawBytes":599,"recorded":"COMPLETE","revision":1,"snapshot":{"bindBackoffUntil":900,"coldStartGrace":false,"ctx":"REST","darkStreak":1,"exact":true,"gateKeptAt":[100,480],"gateLastKeptAt":480,"glance":{"bigTurnRad":1061997773,"bridgeMs":250,"capPerMin":4,"deadbandRadS":1028443341,"maxDtMs":100,"minBoutMs":300,"minBoutRad":1048911544,"noveltyMinRad":1050253722,"noveltyWindowMs":20000,"onsetRadS":1058642330,"releaseRadS":1050253722,"restCapPerMin":2,"settleMs":1500},"keptAt":[100,200],"lastBriefAt":450,"lastCheckHash":["1111222233334444","5555666677778888"],"lastKeyframeAt":500,"lastPhotoAttemptAtMs":250,"planGen":7,"planHash":"abc123def456","pose":{"quietSinceMs":800,"qx":1036831949,"qy":0,"qz":-1110651699,"rx":1056964608,"ry":-1098907648,"rz":0,"walked":true},"resting":true,"ring":[["0123456789abcdef","fedcba9876543210"]],"sampleEvery":1,"settingsHash":"a1b2c3d4e5f6","startups":[300,420,510],"stillStep":2,"tier":"T0"},"startElapsedMs":1000,"startPhoneWallMs":1700000001250,"startWallMs":1700000001000,"traceId":"trace-0001-golden","traceSchema":1,"traceSha256":"$GOLDEN_TRACE_SHA"}"""

    fun gzipB64(raw: ByteArray): String {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(raw) }
        return Base64.getEncoder().encodeToString(bo.toByteArray())
    }

    fun sha(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
