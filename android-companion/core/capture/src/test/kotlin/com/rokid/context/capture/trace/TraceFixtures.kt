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

    /** One of every event type, in time order (the golden chunk). */
    val events: List<TraceEvent> = listOf(
        TraceEvent.Gyro(1000, 1.0f, -1.0f, 0.5f),
        TraceEvent.Accel(1004, 0f, 0f, 9.80665f),
        TraceEvent.Ctx(1010, ContextState.REST),
        TraceEvent.Resting(1010, true),
        TraceEvent.Profile(1020, CameraWake.SETTLE, 10_000, 30_000, null, 60_000, 333, BudgetTier.T0, lowPower = false, heatSlow = true),
        TraceEvent.GateParams(1020, 10, 24, true),
        TraceEvent.Plan(1020, 7, "abc123def456", PlanMode.SHADOW),
        TraceEvent.Episode(2000, true, EpisodeCause.TIMER, null),
        TraceEvent.Score(2500, 42, 0x0123456789abcdefL, -0x0123456789abcdf0L, 12.5f, true, MotionState.STILL, dwellArmed = false, force = false),
        TraceEvent.Gate(2600, 42, GateOutcome.KEEP, ""),
        TraceEvent.Flush(2700, "window"),
        TraceEvent.Backoff(2800, 2),
        TraceEvent.Blocked(2900, null),
        TraceEvent.Episode(3000, false, EpisodeCause.DWELL, 2900),
        TraceEvent.PreRollEnd(11_000),
        TraceEvent.Close(12_000, "stop"),
    )

    val goldenPayload = """["PCT1","trace-0001-golden",0,16]
["g",1000,1065353216,-1082130432,1056964608]
["a",1004,0,0,1092413450]
["cx",1010,"REST"]
["rs",1010,true]
["pf",1020,"SETTLE",10000,30000,null,60000,333,"T0",false,true]
["gp",1020,10,24,true]
["pl",1020,7,"abc123def456","SHADOW"]
["ep",2000,true,"timer",null]
["sc",2500,42,"0123456789abcdef","fedcba9876543210",1095237632,true,"STILL",false,false]
["gt",2600,42,"keep",""]
["fl",2700,"window"]
["bo",2800,2]
["bl",2900,null]
["ep",3000,false,"dwell",2900]
["pr",11000]
["cl",12000,"stop"]
"""
    const val GOLDEN_RAW_BYTES = 526
    const val GOLDEN_SHA = "234830094aa0a69cd178be01c28fd6420b4324baf70fed450aad1d03d375c6af"
    /** A gzip of [goldenPayload] made by a different implementation (python gzip, mtime 0). */
    const val GOLDEN_B64 = "H4sIAAAAAAAC/0VRTWuDQBC992fMeYXZT/VYUqGFQEsVegg9GF0ldKtBDenP78xm014WfG/emzfPA7ztGgkCtqXtfIaIMhvn0PsJBArpPh8OMIKQRNDjrLZaSScyiYWSGo1WBFtXOuOw4OE2DhsSs6BURmpjkZnuhymJAt6rugGGljVB23LxDJwHBhTN1FXT7KvbZhQ6vtMlBOFugNYCGgQxtGH1f/rxnPTkqcy/bUhwLqA9dlLp3g/GOrq7fn58ev2IaTyJFZuzjBo5ffsF4lJm145YS6xRApAsSJ8XJdmRFxkNvu+ObVnkzlIrEnljaZXOHXV0c6ybl/3+Hjm+MfNGxi4Zf3lOATHPQKlVTgRcT1M/XyN4nAksCFTxi0fKVM39Bi4rLYH+6kOaiUXQQZIrjT+EW4kHw7rNZ7L/BZKKDlcOAgAA"
    const val GOLDEN_TRACE_SHA = "6634e53a372f7f3f72d82f5236e5e9456104511b90504034b129a3aeb2424f5b"

    val snapshot = StartSnapshot(
        settingsHash = "a1b2c3d4e5f6", ring = listOf(RingHash(0x0123456789abcdefL, -0x0123456789abcdf0L)), lastKeyframeAt = 500, keptAt = listOf(100, 200),
        planGen = 7, planHash = "abc123def456", glance = GlanceParams(), ctx = ContextState.REST, resting = true, tier = BudgetTier.T0, sampleEvery = 1, exact = true,
    )

    fun manifest(
        chunkCount: Int = 1, eventCount: Long = 16, rawBytes: Long = GOLDEN_RAW_BYTES.toLong(), traceSha: String = GOLDEN_TRACE_SHA,
        recorded: TraceStatus = TraceStatus.COMPLETE, snap: StartSnapshot = snapshot, id: String = ID,
    ) = Manifest(
        traceId = id, installId = "install-abc", deletionCounter = 3, revision = 1, parentTraceId = null, bootId = "boot-42",
        startElapsedMs = 1000, endElapsedMs = 12_000, startWallMs = 1_700_000_001_000, endWallMs = 1_700_000_012_010, phoneOffsetMs = 250, offsetUncertaintyMs = 40,
        chunkCount = chunkCount, eventCount = eventCount, rawBytes = rawBytes, traceSha256 = traceSha, recorded = recorded, closeReason = "stop", snapshot = snap,
    )

    val goldenManifest = """{"bootId":"boot-42","chunkCount":1,"closeReason":"stop","deletionCounter":3,"endElapsedMs":12000,"endWallMs":1700000012010,"eventCount":16,"installId":"install-abc","offsetUncertaintyMs":40,"parentTraceId":null,"phoneOffsetMs":250,"rawBytes":526,"recorded":"COMPLETE","revision":1,"snapshot":{"ctx":"REST","exact":true,"glance":{"bigTurnRad":1061997773,"bridgeMs":250,"capPerMin":4,"deadbandRadS":1028443341,"maxDtMs":100,"minBoutMs":300,"minBoutRad":1048911544,"noveltyMinRad":1050253722,"noveltyWindowMs":20000,"onsetRadS":1058642330,"releaseRadS":1050253722,"restCapPerMin":2,"settleMs":1500},"keptAt":[100,200],"lastKeyframeAt":500,"planGen":7,"planHash":"abc123def456","resting":true,"ring":[["0123456789abcdef","fedcba9876543210"]],"sampleEvery":1,"settingsHash":"a1b2c3d4e5f6","tier":"T0"},"startElapsedMs":1000,"startWallMs":1700000001000,"traceId":"trace-0001-golden","traceSchema":1,"traceSha256":"$GOLDEN_TRACE_SHA"}"""

    fun gzipB64(raw: ByteArray): String {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(raw) }
        return Base64.getEncoder().encodeToString(bo.toByteArray())
    }

    fun sha(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    /** A hand-built chunk (bypasses ChunkCodec.encode) from raw payload text. */
    fun rawChunk(text: String, traceId: String = ID, seq: Int = 0) =
        Triple(traceId to seq, sha(text.toByteArray()), gzipB64(text.toByteArray()))
}
