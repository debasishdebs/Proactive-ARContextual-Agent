package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.keyframe.GateStore
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.WearDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import java.nio.ByteBuffer
import kotlin.random.Random

/** Synthetic scenes: random grey rectangles plus fine texture, so the real blur and hash code has something to see. */
object Scenes {
    private val cache = HashMap<Int, Thumb>()

    /** [soft] divides the contrast: 1 = sharp, 100 = a blurred frame (below the blur floor). */
    fun thumb(id: Int, soft: Int = 1): Thumb = cache.getOrPut(id * 1000 + soft) {
        val r = Random(id * 7919L + 13)
        val luma = IntArray(160 * 120) { 128 }
        repeat(40) {
            val x0 = r.nextInt(160); val y0 = r.nextInt(120); val w = 10 + r.nextInt(60); val h = 10 + r.nextInt(50); val v = r.nextInt(256)
            for (y in y0 until minOf(120, y0 + h)) for (x in x0 until minOf(160, x0 + w)) luma[y * 160 + x] = v
        }
        val out = ByteArray(luma.size) { i ->
            val v = (luma[i] + r.nextInt(41) - 20).coerceIn(0, 255)
            (128 + (v - 128) / soft).toByte()
        }
        Thumb(160, 120, out)
    }

    /** Pixel payload that records which scene it shows and when it was captured, so tests can read a keyframe's origin. */
    fun tag(scene: Int, atMs: Long): ByteArray = ByteBuffer.allocate(12).putInt(scene).putLong(atMs).array()
    fun sceneOf(jpeg: ByteArray): Int = ByteBuffer.wrap(jpeg).int
    fun capturedAtOf(jpeg: ByteArray): Long = ByteBuffer.wrap(jpeg).getLong(4)

    /** A frame below the dark floor (mean luma 8). */
    fun dark(atMs: Long) = FrameCopy(Thumb(160, 120, ByteArray(160 * 120) { 3 }), Nv21Frame(640, 480, 0, tag(0, atMs)))

    fun frame(scene: Int, soft: Int, atMs: Long) = FrameCopy(thumb(scene, soft), Nv21Frame(640, 480, 0, tag(scene, atMs)))
}

class MemStore : GateStore {
    val ring = ArrayDeque<Pair<Long, Long>>()
    var kept = 0L
    override fun lastKeyframes() = ring.toList()
    override fun push(d: Long, p: Long) { ring.addLast(d to p); while (ring.size > 5) ring.removeFirst() }
    override fun lastKeptAt() = kept
    override fun setLastKeptAt(t: Long) { kept = t }
}

/**
 * A camera with an injectable start-up latency (first frame [startupMs] after open) and frame interval, in virtual time.
 * [frames] makes the frame for the instant it is taken.
 */
class FakeCamera(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    var startupMs: () -> Long = { 535L },
    private val frameIntervalMs: Long = 40L,
    var frames: (Long) -> FrameCopy?,
) : SamplingCamera {
    /** open() takes this long before returning (a slow bind on the main thread), then the first frame comes [startupMs] later. */
    var bindDelayMs = 0L
    /** The first this-many frames after each open are too dark to use (auto-exposure still ramping). */
    var darkFrames = 0
    /** open() fails because another app holds the camera. */
    var busyOpens = false
    override val busy: Boolean get() = busyOpens
    /** open() never returns (a hung provider future). */
    var hangOpens = false
    /** Frames stop after this many have been delivered per open (null = never). */
    var stallAfterFrames: Int? = null
    var isOpen = false; private set
    var opens = 0; private set
    var closes = 0; private set
    var failOpens = 0
    val events = ArrayList<String>()
    val openTimes = ArrayList<Long>()
    private var gen: Job? = null

    override suspend fun open(onFrame: (FrameSource) -> Unit): Boolean {
        opens++
        events += "open@${now()}"
        openTimes += now()
        if (hangOpens) delay(1_000_000L)
        if (bindDelayMs > 0) delay(bindDelayMs)
        if (busyOpens) return false
        if (failOpens > 0) { failOpens--; return false }
        isOpen = true
        val startup = startupMs()
        gen = scope.launch {
            delay(startup)
            var n = 0
            while (isActive) {
                val t = now()
                val dark = n < darkFrames
                if (stallAfterFrames?.let { n >= it } != true) {
                    onFrame(object : FrameSource { override fun copy(): FrameCopy? = if (dark) Scenes.dark(t) else frames(t) })
                }
                n++
                delay(frameIntervalMs)
            }
        }
        return true
    }

    override fun close() {
        if (isOpen) { closes++; events += "close@${now()}" }
        gen?.cancel(); gen = null
        isOpen = false
    }
}

/** Keeps the pixel payload as the "JPEG" so a test can see which candidate was encoded. */
class TagEncoder(var width: Int = 640, var height: Int = 480) : KeyframeEncoder {
    override fun encode(frame: Nv21Frame) = EncodedKeyframe(frame.data.copyOf(), width, height)
}

class Rig(
    val test: TestScope,
    var settings: HudSettings = HudSettings(captureMode = CaptureMode.SMART),
    startupMs: () -> Long = { 535L },
    var scene: Int = 1,
    var soft: Int = 1,
    val encoder: KeyframeEncoder = TagEncoder(),
    var work: Int = 90, var rest: Int = 15,
    val workRest: WorkRest? = null,
) {
    val sched = test.testScheduler
    val clock: () -> Long = { sched.currentTime }
    val motion = MotionClassifier(clock)
    val wear = WearDetector(clock)
    val store = MemStore()
    val gate = KeyframeGate(store, clock)
    var heatReading = HeatReading(0, Float.NaN, 30f)
    val heat = HeatGuard({ heatReading }, clock)
    val sessionWorkRest = workRest ?: WorkRest(clock, { work }, { rest })
    val owner = CameraOwner()
    val camera = FakeCamera(test.backgroundScope, clock, startupMs) { t -> Scenes.frame(scene, soft, t) }
    val keyframes = ArrayList<Pair<Long, KeyframeOut>>()
    var paused = false
    var fallbacks = 0
    var onFallbackAction: suspend () -> Unit = {}
    var gyroMag = 0f
    var profile: CaptureProfile = CaptureProfile.DEFAULT
    var phoneInUse = false
    var fixedCaptures = ArrayList<Long>()
    var fixedDuring: suspend () -> Unit = {}
    val logs = ArrayList<String>()

    val session = SamplingSession(
        scope = test.backgroundScope, camera = camera, owner = owner, motion = motion, wear = wear, gate = gate, heat = heat,
        workRest = sessionWorkRest, encoder = encoder, sink = { k, _ -> keyframes += clock() to k }, clock = clock, settings = { settings },
        capturePaused = { paused }, profile = { profile }, phoneInUse = { phoneInUse }, onFallback = { fallbacks++; test.backgroundScope.launch { onFallbackAction() } }, log = { logs += it },
        fixedCapture = { owner.acquire("fixed") { fixedCaptures += clock(); fixedDuring() } },
    )

    /** The sensor feed: gyro at 50 Hz from [gyroMag], and the heat poll every 30 s like the service does. */
    fun startSensors() {
        test.backgroundScope.launch { while (isActive) { motion.onGyro(gyroMag, 0f, 0f, clock()); delay(20) } }
        val poller = HeatPoller(heat, clock)
        test.backgroundScope.launch { while (isActive) { poller.tick(); delay(1000) } }
    }

    suspend fun advance(ms: Long) = delay(ms)
    val scenesKept get() = keyframes.map { Scenes.sceneOf(it.second.jpeg) }
}
