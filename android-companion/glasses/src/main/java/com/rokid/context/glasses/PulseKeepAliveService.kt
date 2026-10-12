package com.rokid.context.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.os.BatteryManager
import android.os.Build
import android.app.ActivityManager
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.view.Surface
import kotlin.coroutines.resume
import kotlin.math.min

class PulseKeepAliveService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sender by lazy { ServiceBluetoothFrameSender(this) }
    private val scheduler by lazy { PulseCaptureScheduler(this) }
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val pulseInFlight = AtomicBoolean(false)
    private var lastFrameHash: Long? = null
    private var lastSentAtMs: Long = 0L
    private var lastForegroundFallbackAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (prefs.contains(KEY_LAST_HASH)) {
            lastFrameHash = prefs.getLong(KEY_LAST_HASH, 0L)
        }
        lastSentAtMs = prefs.getLong(KEY_LAST_SENT_AT, 0L)
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action != ACTION_PULSE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Pulse running"))
        publishHudStatus("Pulse wakeup received")
        runPulse(startId)
        return START_NOT_STICKY
    }

    private fun runPulse(startId: Int) {
        if (!pulseInFlight.compareAndSet(false, true)) {
            stopSelf(startId)
            return
        }
        scope.launch {
            try {
                publishHudStatus("Pulse started. Capturing frame...")
                var outcome: CaptureOutcome = runCatching { captureAndTransferOnce() }
                    .getOrElse { CaptureOutcome.Failed(it.message ?: "capture failure") }
                var attempt = 1
                while (
                    attempt < MAX_CAPTURE_ATTEMPTS &&
                    outcome is CaptureOutcome.Failed &&
                    outcome.statusText == "no transferable frame"
                ) {
                    attempt += 1
                    Log.i(TAG, "Retrying pulse capture attempt $attempt/$MAX_CAPTURE_ATTEMPTS")
                    publishHudStatus("Retry capture attempt $attempt/$MAX_CAPTURE_ATTEMPTS")
                    outcome = runCatching { captureAndTransferOnce() }
                        .getOrElse { CaptureOutcome.Failed(it.message ?: "capture failure") }
                }

                val launchedFallback = maybeLaunchPulseActivityFallback(outcome)
                if (launchedFallback) {
                    scheduler.scheduleNext(RETRY_PULSE_INTERVAL_MS)
                    publishHudStatus("Foreground fallback requested")
                    Log.i(TAG, "Requested foreground pulse fallback due background camera restriction")
                    return@launch
                }

                val nextDelay = when {
                    isBatterySaverOn() -> POWER_SAVER_PULSE_INTERVAL_MS
                    outcome is CaptureOutcome.Sent -> if (batteryPercent() in 0..20) LOW_BATTERY_PULSE_INTERVAL_MS else DEFAULT_PULSE_INTERVAL_MS
                    outcome is CaptureOutcome.Skipped -> DEFAULT_PULSE_INTERVAL_MS
                    else -> RETRY_PULSE_INTERVAL_MS
                }
                if (outcome is CaptureOutcome.Failed) {
                    publishHudEvent(PulseHudEventType.FAILED)
                }
                scheduler.scheduleNext(nextDelay)

                publishHudStatus(outcome.statusText)
                Log.i(TAG, "Pulse outcome: ${outcome.statusText}. Next in ${nextDelay / 1000}s")
            } finally {
                pulseInFlight.set(false)
                stopForegroundAndSelf(startId)
            }
        }
    }

    private suspend fun captureAndTransferOnce(): CaptureOutcome {
        if (isBatterySaverOn()) {
            publishHudStatus("Policy block: battery saver on")
            return CaptureOutcome.Skipped("Policy block: battery saver on")
        }
        if (batteryPercent() in 0..10 && !isCharging()) {
            publishHudStatus("Policy block: battery low")
            return CaptureOutcome.Skipped("Policy block: battery low")
        }

        val provider = awaitCameraProvider() ?: return CaptureOutcome.Failed("camera provider unavailable")
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val previewTexture = SurfaceTexture(0)
        var previewSurface: Surface? = null
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider { request ->
                    val size = request.resolution
                    previewTexture.setDefaultBufferSize(size.width, size.height)
                    val surface = Surface(previewTexture)
                    previewSurface = surface
                    request.provideSurface(surface, cameraExecutor) {
                        runCatching { surface.release() }
                        runCatching { previewTexture.release() }
                    }
                }
            }

        val resultReady = kotlinx.coroutines.CompletableDeferred<CaptureOutcome>()
        val completed = AtomicBoolean(false)
        val analyzedFrames = AtomicInteger(0)
        var lastAnalyzeAtMs = 0L

        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (completed.get()) return@setAnalyzer
                val count = analyzedFrames.incrementAndGet()
                if (count <= 3 || count % 15 == 0) {
                    Log.i(TAG, "Service analyzed frame #$count")
                }
                val now = System.currentTimeMillis()
                if (now - lastAnalyzeAtMs < PULSE_CAPTURE_INTERVAL_MS) return@setAnalyzer
                lastAnalyzeAtMs = now

                val outcome = evaluateAndMaybeSend(image, now) ?: return@setAnalyzer
                if (completed.compareAndSet(false, true)) {
                    resultReady.complete(outcome)
                }
            } catch (error: Throwable) {
                if (completed.compareAndSet(false, true)) {
                    resultReady.complete(CaptureOutcome.Failed("analyze failure: ${error.message}"))
                }
            } finally {
                image.close()
            }
        }

        provider.unbindAll()
        val selector = resolveCameraSelector(provider)
        runCatching {
            val boundCamera = provider.bindToLifecycle(this, selector, analysis, preview)
            val lensLabel = when (boundCamera.cameraInfo.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> "back"
                CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }
            Log.i(TAG, "Pulse service bound camera lens=$lensLabel")
        }.onFailure {
            provider.unbindAll()
            return CaptureOutcome.Failed("bind failure: ${it.message}")
        }

        val outcome = withTimeoutOrNull(PULSE_WINDOW_MS) { resultReady.await() }
            ?: if (analyzedFrames.get() == 0) {
                CaptureOutcome.Failed("camera restricted in background")
            } else {
                CaptureOutcome.Failed("no transferable frame")
            }

        if (outcome is CaptureOutcome.Failed) {
            Log.w(
                TAG,
                "Capture failed: ${outcome.statusText}; analyzedFrames=${analyzedFrames.get()}",
            )
        }

        provider.unbindAll()
        runCatching { previewSurface?.release() }
        runCatching { previewTexture.release() }
        return outcome
    }

    private fun evaluateAndMaybeSend(image: ImageProxy, nowMs: Long): CaptureOutcome? {
        val yData = image.yPlaneToPackedByteArray()
        if (yData.isEmpty()) return null

        val jpegRaw = yuv420ToJpeg(image, JPEG_QUALITY_CAPTURE)
        if (jpegRaw.isEmpty()) return null

        val prepared = prepareFrameForSend(
            jpegRaw = jpegRaw,
            quality = JPEG_QUALITY,
            maxWidth = MAX_SEND_WIDTH,
            rotationDegrees = image.imageInfo.rotationDegrees,
        ) ?: return null

        val hashDelta = lastFrameHash?.let { hammingDistance(it, prepared.hash) } ?: 64
        val battery = batteryPercent()
        val charging = isCharging()

        val policyReason = when {
            battery in 0..10 && !charging -> "Policy block: battery ${battery}% and not charging"
            prepared.meanLuma < DARK_MEAN_THRESHOLD -> "Policy block: dark/black frame (mean=${"%.1f".format(Locale.US, prepared.meanLuma)})"
            hashDelta <= DEDUP_HAMMING_THRESHOLD -> "Policy block: dedup (hash delta=$hashDelta)"
            nowMs - lastSentAtMs < MIN_SEND_INTERVAL_MS -> "Policy block: send rate limit"
            else -> null
        }
        if (policyReason != null) {
            Log.i(TAG, policyReason)
            publishHudStatus(policyReason)
            return CaptureOutcome.Skipped(policyReason)
        }

        publishHudStatus("Frame accepted. Sending to phone...")
        publishHudEvent(PulseHudEventType.CAPTURED)
        val frameId = "rokid_${nowMs}"
        val ack = runCatching {
            sender.sendFrame(
                frameId = frameId,
                imageJpeg = prepared.jpeg,
                lumaMean = prepared.meanLuma,
                hashDelta = hashDelta,
            )
        }.getOrElse { "send_failed: ${it.message}" }

        if (ack.startsWith("send_failed", ignoreCase = true)) {
            Log.e(TAG, ack)
            publishHudStatus(ack)
            publishHudEvent(PulseHudEventType.FAILED)
            return CaptureOutcome.Failed(ack)
        }

        publishHudStatus("Frame sent: $frameId")
        publishHudEvent(PulseHudEventType.SENT)
        lastFrameHash = prepared.hash
        lastSentAtMs = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_HASH, prepared.hash)
            .putLong(KEY_LAST_SENT_AT, lastSentAtMs)
            .apply()
        Log.i(TAG, "Sent $frameId (${prepared.jpeg.size / 1024}KB) -> $ack")
        return CaptureOutcome.Sent(frameId)
    }

    private fun publishHudEvent(type: PulseHudEventType) {
        PulseHudEventStore.publish(this, type)
    }

    private fun publishHudStatus(text: String) {
        PulseHudEventStore.publishStatus(this, text)
    }

    private fun maybeLaunchPulseActivityFallback(outcome: CaptureOutcome): Boolean {
        if (outcome !is CaptureOutcome.Failed) return false
        val lower = outcome.statusText.lowercase(Locale.US)
        val cameraRestrictedFailure = lower.contains("camera restricted in background")
        val likelyBackgroundCameraRestriction =
            lower.contains("no transferable frame") && isBackgroundRestricted()
        if (!cameraRestrictedFailure && !likelyBackgroundCameraRestriction) return false

        val now = System.currentTimeMillis()
        if (now - lastForegroundFallbackAtMs < FOREGROUND_FALLBACK_COOLDOWN_MS) {
            Log.i(TAG, "Skipping fallback launch due cooldown")
            return false
        }

        val mode = GlassesSettingsStore.silencyMode(this).persistedValue
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_PULSE_CAPTURE
            putExtra(MainActivity.EXTRA_SILENCY_MODE, mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val started = runCatching {
            startActivity(intent)
            true
        }.getOrElse {
            Log.e(TAG, "Foreground pulse fallback launch failed", it)
            false
        }
        if (started) {
            lastForegroundFallbackAtMs = now
            publishHudStatus("Camera restricted; requested HUD pulse (reopen app if not visible)")
        }
        return started
    }

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

    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Rokid Pulse",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Rokid Pulse")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun stopForegroundAndSelf(startId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf(startId)
    }

    companion object {
        private const val TAG = "RokidPulseService"
        private const val CHANNEL_ID = "rokid_pulse_keepalive"
        private const val NOTIFICATION_ID = 30072

        private const val DEFAULT_PULSE_INTERVAL_MS = 30_000L
        private const val LOW_BATTERY_PULSE_INTERVAL_MS = 60_000L
        private const val POWER_SAVER_PULSE_INTERVAL_MS = 60_000L
        private const val RETRY_PULSE_INTERVAL_MS = 15_000L
        private const val PULSE_WINDOW_MS = 8_000L
        private const val PULSE_CAPTURE_INTERVAL_MS = 1_000L
        private const val MAX_CAPTURE_ATTEMPTS = 2
        private const val MIN_SEND_INTERVAL_MS = 2_500L
        private const val DARK_MEAN_THRESHOLD = 8.0
        private const val DEDUP_HAMMING_THRESHOLD = 4
        private const val JPEG_QUALITY_CAPTURE = 72
        private const val JPEG_QUALITY = 62
        private const val MAX_SEND_WIDTH = 360
        private const val FOREGROUND_FALLBACK_COOLDOWN_MS = 20_000L

        private const val PREFS_NAME = "rokid_pulse_sender"
        private const val KEY_LAST_HASH = "last_hash"
        private const val KEY_LAST_SENT_AT = "last_sent_at"

        const val ACTION_PULSE = "com.rokid.context.glasses.action.PULSE_SERVICE"

        fun startPulse(context: Context) {
            val intent = Intent(context, PulseKeepAliveService::class.java).apply {
                action = ACTION_PULSE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private data class PreparedFrame(
        val jpeg: ByteArray,
        val meanLuma: Double,
        val hash: Long,
    )

    private sealed class CaptureOutcome(val statusText: String) {
        class Sent(val frameId: String) : CaptureOutcome("Frame transferred: $frameId")
        class Skipped(reason: String) : CaptureOutcome(reason)
        class Failed(reason: String) : CaptureOutcome(reason)
    }
}

private class ServiceBluetoothFrameSender(
    private val context: Context,
) {
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @SuppressLint("MissingPermission")
    fun sendFrame(
        frameId: String,
        imageJpeg: ByteArray,
        lumaMean: Double,
        hashDelta: Int,
    ): String {
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        if (!hasConnectPermission()) throw IOException("Bluetooth connect permission missing")

        val device = bestPhoneCandidate(adapter.bondedDevices.toList())
            ?: throw IOException("No paired phone candidate found")

        runCatching { adapter.cancelDiscovery() }
        val socket = connectSocket(device)
        socket.use {
            val payload = JSONObject()
                .put("type", "rokid_frame")
                .put("frame_id", frameId)
                .put("ext", "jpg")
                .put("image_b64", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
                .put(
                    "meta",
                    JSONObject()
                        .put("compressed_on", "glasses")
                        .put("luma_mean", lumaMean)
                        .put("hash_delta", hashDelta)
                        .put("size_bytes", imageJpeg.size),
                )

            val output = DataOutputStream(it.outputStream)
            val input = DataInputStream(it.inputStream)
            writeJson(output, payload)
            val ack = readJson(input)
            applyAckConfig(ack)
            return ack.optString("message", "ack")
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestPhoneCandidate(devices: List<BluetoothDevice>): BluetoothDevice? {
        return devices
            .sortedByDescending { it.phoneCandidateScore() }
            .firstOrNull { it.phoneCandidateScore() > -100 }
            ?: devices.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.phoneCandidateScore(): Int {
        val name = (name ?: address).lowercase(Locale.US)
        var score = when (bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.PHONE -> 100
            BluetoothClass.Device.Major.PERIPHERAL -> -100
            else -> 0
        }
        if (
            name.contains("phone") ||
            name.contains("samsung") ||
            name.contains("galaxy") ||
            name.contains("pixel") ||
            name.contains("oneplus") ||
            name.contains("iphone")
        ) {
            score += 50
        }
        return score
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val strategies = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(RokidBluetoothProtocol.APP_UUID) },
            { device.createRfcommSocketToServiceRecord(RokidBluetoothProtocol.APP_UUID) },
            {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )

        var lastError: Exception? = null
        for (strategy in strategies) {
            val socket = try {
                strategy()
            } catch (error: Exception) {
                lastError = error
                continue
            }
            try {
                socket.connect()
                return socket
            } catch (error: Exception) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw IOException(lastError?.message ?: "Unable to connect to phone")
    }

    private fun writeJson(output: DataOutputStream, payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun readJson(input: DataInputStream): JSONObject {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Invalid control message length: $length")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return JSONObject(String(body, Charsets.UTF_8))
    }

    private fun applyAckConfig(ack: JSONObject) {
        val config = ack.optJSONObject("config") ?: return
        val rawMode = config.optString("silency_mode", "").ifBlank { return }
        val mode = SilencyMode.fromPersisted(rawMode)
        GlassesSettingsStore.setSilencyMode(context, mode)
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
    }
}
