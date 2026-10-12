package com.rokid.context.glasses.capture

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.rokid.context.glasses.BufferResult
import com.rokid.context.glasses.BuildConfig
import com.rokid.context.glasses.GlassesRuntime
import com.rokid.context.glasses.link.HudSettingsCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The only capture path (spec §3.1): a foreground camera service woken by [CaptureScheduler] (or [captureNow]). It
 * checks the local pause set first (fail-closed), takes one frame, applies [FrameGuard], encodes JPEG q62 @ 360 px and
 * buffers it; [GlassesRuntime.onFrameBuffered] then flushes when the session is open. Ported from PulseKeepAliveService
 * without its RFCOMM sender. While paused the alarm keeps ticking but nothing is captured, so a timed pause ends on time.
 */
class CaptureService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler by lazy { CaptureScheduler(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val runtime by lazy { GlassesRuntime.get(this) }
    private val inFlight = AtomicBoolean(false)

    private enum class Outcome { BUFFERED, SKIPPED, FAILED }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action != ACTION_SCHEDULED && action != ACTION_NOW) { stopSelf(startId); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (!inFlight.compareAndSet(false, true)) return START_NOT_STICKY // the running capture stops the service
        val skipDedupe = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SKIP_DEDUPE, false) == true
        scope.launch {
            var failed = false
            try {
                runtime.pauses.ensureLoaded()
                failed = captureOnce(skipDedupe) == Outcome.FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "capture failed: ${e.javaClass.simpleName}")
            } finally {
                val s = HudSettingsCache(getSharedPreferences(HudSettingsCache.PREFS, Context.MODE_PRIVATE)).read()
                scheduler.scheduleNext(CaptureCadence.nextDelayMs(s.captureIntervalSec, batteryPercent(), isBatterySaverOn(), failed))
                runtime.onCaptureScheduled()
                inFlight.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun captureOnce(skipDedupe: Boolean): Outcome {
        if (!runtime.pauses.captureAllowed()) { Log.i(TAG, "capture skipped: paused"); return Outcome.SKIPPED }
        if (isBatterySaverOn()) { Log.i(TAG, "capture skipped: battery saver"); return Outcome.SKIPPED }
        // Low battery skips before the camera opens; FrameGuard checks again per frame. Unknown battery fails open.
        if (FrameGuard.lowBattery(batteryPercent(), isCharging())) { Log.i(TAG, "capture skipped: ${SkipReason.LOW_BATTERY}"); return Outcome.SKIPPED }
        val provider = awaitCameraProvider() ?: return Outcome.FAILED
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val texture = SurfaceTexture(0)
        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider { request ->
                texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(texture)
                request.provideSurface(surface, cameraExecutor) { runCatching { surface.release() }; runCatching { texture.release() } }
            }
        }
        val result = CompletableDeferred<Outcome>()
        val analyzed = AtomicInteger(0)
        var lastAnalyzeAtMs = 0L
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (result.isCompleted) return@setAnalyzer
                // Paused mid-window: stop now so the camera unbinds promptly (spec §5.5).
                if (!runtime.pauses.captureAllowed()) { Log.i(TAG, "capture skipped: paused"); result.complete(Outcome.SKIPPED); return@setAnalyzer }
                analyzed.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastAnalyzeAtMs < ANALYZE_INTERVAL_MS) return@setAnalyzer
                lastAnalyzeAtMs = now
                evaluate(image, now, skipDedupe)?.let { result.complete(it) }
            } catch (e: Exception) {
                Log.w(TAG, "analyze failed: ${e.javaClass.simpleName}")
                result.complete(Outcome.FAILED)
            } finally {
                image.close()
            }
        }
        provider.unbindAll()
        if (runCatching { provider.bindToLifecycle(this, resolveCameraSelector(provider), analysis, preview) }.isFailure) {
            provider.unbindAll()
            Log.w(TAG, "camera bind failed")
            return Outcome.FAILED
        }
        val outcome = withTimeoutOrNull(WINDOW_MS) { result.await() } ?: run {
            if (analyzed.get() == 0 || isBackgroundRestricted()) runtime.fallback.maybeLaunch(this)
            Outcome.FAILED
        }
        provider.unbindAll()
        runCatching { texture.release() }
        return outcome
    }

    /** Null = this camera frame is unusable; the analyzer tries the next one. */
    private fun evaluate(image: ImageProxy, nowMs: Long, skipDedupe: Boolean): Outcome? {
        if (image.yPlaneToPackedByteArray().isEmpty()) return null
        val raw = yuv420ToJpeg(image, JPEG_QUALITY_CAPTURE).takeIf { it.isNotEmpty() } ?: return null
        val prepared = prepareFrameForSend(jpegRaw = raw, quality = JPEG_QUALITY, maxWidth = MAX_SEND_WIDTH, rotationDegrees = image.imageInfo.rotationDegrees) ?: return null
        val lastHash = if (prefs.contains(KEY_LAST_HASH)) prefs.getLong(KEY_LAST_HASH, 0L) else null
        val battery = batteryPercent()
        val skip = FrameGuard.check(battery, isCharging(), prepared.meanLuma, prepared.hash, lastHash, nowMs, prefs.getLong(KEY_LAST_KEPT_AT, 0L), skipDedupe)
        if (skip != null) { Log.i(TAG, "capture skipped: $skip"); return Outcome.SKIPPED }
        val frameId = "g${nowMs}_${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}"
        when (runtime.bufferCaptured(frameId, prepared.jpeg, nowMs, prepared.meanLuma.roundToInt(), java.lang.Long.toHexString(prepared.hash), battery.takeIf { it in 0..100 })) {
            BufferResult.STORED -> Unit
            BufferResult.PAUSED -> { Log.i(TAG, "capture skipped: paused"); return Outcome.SKIPPED }
            // OutboundBuffer logged the exception class; the capture fails and retries in 15 s (CaptureCadence.RETRY_MS).
            BufferResult.NOT_STORED -> { Log.w(TAG, "capture failed: frame $frameId not buffered"); return Outcome.FAILED }
        }
        prefs.edit().putLong(KEY_LAST_HASH, prepared.hash).putLong(KEY_LAST_KEPT_AT, nowMs).apply()
        Log.i(TAG, "buffered $frameId (${prepared.jpeg.size / 1024} KB) buffer=${runtime.buffer.size}")
        runtime.onFrameBuffered()
        return Outcome.BUFFERED
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Proactive Context")
        .setContentText("Capturing a frame")
        .setOngoing(true)
        .build()

    // ---- image helpers copied from PulseKeepAliveService.kt (Step 6) ----
    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isBackgroundRestricted == true
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider? {
        return suspendCancellableCoroutine { continuation ->
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener(
                {
                    val provider = runCatching { providerFuture.get() }.getOrNull()
                    if (continuation.isActive) continuation.resume(provider)
                },
                ContextCompat.getMainExecutor(this),
            )
        }
    }

    private fun resolveCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        val backAvailable = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        if (backAvailable) return CameraSelector.DEFAULT_BACK_CAMERA
        val frontAvailable = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (frontAvailable) return CameraSelector.DEFAULT_FRONT_CAMERA
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isBatterySaverOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode == true
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun yuv420ToJpeg(image: ImageProxy, quality: Int): ByteArray {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return ByteArray(0)
        val nv21 = yuv420ToNv21(image)
        if (nv21.isEmpty()) return ByteArray(0)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        return ByteArrayOutputStream().use { out ->
            val ok = yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            if (!ok) return ByteArray(0)
            out.toByteArray()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return ByteArray(0)

        val out = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var dst = 0
        for (row in 0 until height) {
            val rowOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                val idx = rowOffset + (col * yPlane.pixelStride)
                if (idx < yBuffer.limit() && dst < width * height) {
                    out[dst++] = yBuffer.get(idx)
                } else if (dst < width * height) {
                    out[dst++] = 0
                }
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val vIdx = vRowOffset + (col * vPlane.pixelStride)
                val uIdx = uRowOffset + (col * uPlane.pixelStride)
                if (dst + 1 >= out.size) break
                out[dst++] = if (vIdx < vBuffer.limit()) vBuffer.get(vIdx) else 0
                out[dst++] = if (uIdx < uBuffer.limit()) uBuffer.get(uIdx) else 0
            }
        }
        while (dst < out.size) out[dst++] = 0
        return out
    }

    private fun ImageProxy.yPlaneToPackedByteArray(): ByteArray {
        if (planes.isEmpty()) return ByteArray(0)
        val plane = planes[0]
        val buffer = plane.buffer
        val width = this.width
        val height = this.height
        if (width <= 0 || height <= 0) return ByteArray(0)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val data = ByteArray(width * height)

        if (pixelStride == 1 && rowStride == width) {
            buffer.rewind()
            buffer.get(data, 0, min(data.size, buffer.remaining()))
            return data
        }

        val row = ByteArray(rowStride.coerceAtLeast(width))
        for (y in 0 until height) {
            val rowStart = min(y * rowStride, buffer.limit())
            buffer.position(rowStart)
            val bytesToRead = min(rowStride, buffer.remaining())
            if (bytesToRead <= 0) break
            buffer.get(row, 0, bytesToRead)
            for (x in 0 until width) {
                val src = x * pixelStride
                if (src < bytesToRead) {
                    data[y * width + x] = row[src]
                }
            }
        }
        return data
    }

    private fun prepareFrameForSend(
        jpegRaw: ByteArray,
        quality: Int,
        maxWidth: Int,
        rotationDegrees: Int,
    ): PreparedFrame? {
        val decoded = BitmapFactory.decodeByteArray(jpegRaw, 0, jpegRaw.size) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) {
            decoded.recycle()
            return null
        }
        val rotated = if (rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
        val scaled = if (rotated.width > maxWidth) {
            val outWidth = maxWidth.coerceAtLeast(1)
            val outHeight = (rotated.height.toFloat() * (outWidth.toFloat() / rotated.width.toFloat()))
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, outWidth, outHeight, true)
        } else {
            rotated
        }
        val meanLuma = sampledLumaMean(scaled)
        val hash = frameHash(scaled)
        val preparedJpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        if (preparedJpeg.isEmpty()) return null
        return PreparedFrame(
            jpeg = preparedJpeg,
            meanLuma = meanLuma,
            hash = hash,
        )
    }

    private fun sampledLumaMean(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.0
        var total = 0.0
        var count = 0
        for (gy in 0 until 12) {
            val y = (gy * (height - 1)) / 11
            for (gx in 0 until 12) {
                val x = (gx * (width - 1)) / 11
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                total += (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
                count += 1
            }
        }
        return if (count > 0) total / count else 0.0
    }

    private fun frameHash(bitmap: Bitmap): Long {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0L
        val sample = IntArray(64)
        var idx = 0
        for (gy in 0 until 8) {
            val y = (gy * (height - 1)) / 7
            for (gx in 0 until 8) {
                val x = (gx * (width - 1)) / 7
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                sample[idx++] = ((0.2126 * r) + (0.7152 * g) + (0.0722 * b)).toInt()
            }
        }
        val avg = sample.average()
        var hash = 0L
        for (i in sample.indices) {
            if (sample[i] >= avg) {
                hash = hash or (1L shl i)
            }
        }
        return hash
    }
    private data class PreparedFrame(
        val jpeg: ByteArray,
        val meanLuma: Double,
        val hash: Long,
    )

    companion object {
        const val TAG = "PcCapture"
        const val ACTION_SCHEDULED = "com.rokid.context.glasses.action.CAPTURE_SCHEDULED"
        const val ACTION_NOW = "com.rokid.context.glasses.action.CAPTURE_SERVICE_NOW"
        /** Debug builds only: ignore dedupe and throttle so adb checks can capture a static scene. */
        const val EXTRA_SKIP_DEDUPE = "skip_dedupe"
        private const val CHANNEL_ID = "pc_capture"
        private const val NOTIFICATION_ID = 30_102
        private const val PREFS = "capture_guard"
        private const val KEY_LAST_HASH = "last_hash"
        private const val KEY_LAST_KEPT_AT = "last_kept_at"
        // Unchanged from PulseKeepAliveService (spec §1.3: JPEG q62 @ 360 px).
        private const val WINDOW_MS = 8_000L
        private const val ANALYZE_INTERVAL_MS = 1_000L
        private const val JPEG_QUALITY_CAPTURE = 72
        private const val JPEG_QUALITY = 62
        private const val MAX_SEND_WIDTH = 360

        fun captureNow(context: Context, skipDedupe: Boolean = false) {
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_NOW).putExtra(EXTRA_SKIP_DEDUPE, skipDedupe))
        }
    }
}
