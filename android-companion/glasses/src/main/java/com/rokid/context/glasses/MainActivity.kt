package com.rokid.context.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
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
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var pulseBadgeContainer: View
    private lateinit var statusText: TextView
    private lateinit var nextPulseText: TextView
    private lateinit var verboseDetailText: TextView
    private lateinit var toggleButton: Button
    private lateinit var silencyButton: Button
    private lateinit var pulseBlinkDot: View
    private lateinit var pulseBadgeText: TextView
    private lateinit var stealthIndicator: View
    private lateinit var pulseEventContainer: View
    private lateinit var pulseEventIcon: ImageView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sender by lazy { BluetoothFrameSender(this) }
    private val pulseScheduler by lazy { PulseCaptureScheduler(this) }
    private val configServer by lazy {
        CompanionConfigServer(this) { mode ->
            runOnUiThread {
                silencyMode = mode
                applySilencyUi()
                setStatus("Silency mode updated from companion: ${silencyModeLabel(mode)}")
            }
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var pulseWindowJob: Job? = null
    private var pulseEarlyCaptureJob: Job? = null
    private var blinkJob: Job? = null
    private var nextPulseTickerJob: Job? = null
    private var pulseEventHideJob: Job? = null
    private var running = false
    private var pulseModeLaunch = false
    private var closeAfterPulse = false
    private var pulseSessionClosing = false
    private var frameTransferredThisSession = false
    private var pendingPulseTrigger: String? = null
    private var lastStatusAtMs = 0L
    private var lastSentAtMs = 0L
    private var lastAnalyzeAtMs = 0L
    private var lastFrameHash: Long? = null
    private var analyzedFrames = 0
    private var silencyMode: SilencyMode = SilencyMode.HUD
    private var lastScheduleRecoveryAtMs = 0L
    private var lastPulseEventAtMs = 0L
    private var lastPulseStatusAtMs = 0L
    private var lastEventType: PulseHudEventType? = null
    private var currentStatusText: String = "Initializing..."
    private val sendInFlight = AtomicBoolean(false)
    private val analyzeInFlight = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = requiredPermissions().all { permission ->
                grants[permission] == true || hasPermission(permission)
            }
            if (granted) {
                startAfterPermission()
            } else {
                setStatus("Camera/Bluetooth permission required")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        pulseBadgeContainer = findViewById(R.id.pulseBadgeContainer)
        statusText = findViewById(R.id.statusText)
        nextPulseText = findViewById(R.id.nextPulseText)
        verboseDetailText = findViewById(R.id.verboseDetailText)
        toggleButton = findViewById(R.id.toggleButton)
        silencyButton = findViewById(R.id.silencyButton)
        pulseBlinkDot = findViewById(R.id.pulseBlinkDot)
        pulseBadgeText = findViewById(R.id.pulseBadgeText)
        stealthIndicator = findViewById(R.id.stealthIndicator)
        pulseEventContainer = findViewById(R.id.pulseEventContainer)
        pulseEventIcon = findViewById(R.id.pulseEventIcon)

        pulseModeLaunch = intent?.action == ACTION_PULSE_CAPTURE
        closeAfterPulse = pulseModeLaunch
        silencyMode = GlassesSettingsStore.silencyMode(this)
        applySilencyOverrideFromIntent(intent)
        applyModeChrome()

        toggleButton.setOnClickListener {
            if (running || pulseWindowJob != null || pulseSessionClosing) return@setOnClickListener
            closeAfterPulse = false
            startPulseSession(trigger = "manual")
        }
        silencyButton.setOnClickListener {
            cycleSilencyMode()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Keep scheduler/camera process eligible by backgrounding instead of destroying activity.
                    setStatus("HUD minimized. Scheduler stays armed.")
                    moveTaskToBack(true)
                }
            },
        )
        startNextPulseTicker()
        configServer.start()

        ensurePermissionsThenStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySilencyOverrideFromIntent(intent)
        if (intent?.action == ACTION_PULSE_CAPTURE) {
            pulseModeLaunch = true
            closeAfterPulse = true
            applyModeChrome()
            val missing = requiredPermissions().filterNot(::hasPermission)
            if (missing.isEmpty()) {
                queuePulseStart(trigger = "scheduled")
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tryStartPendingPulse()
        ensureScheduleHealthy()
        refreshNextPulseLabel()
        renderLatestPulseStatus()
        renderLatestPulseEvent()
    }

    override fun onStop() {
        super.onStop()
        recoverScheduleAfterUiExit()
    }

    override fun onDestroy() {
        recoverScheduleAfterUiExit()
        pulseWindowJob?.cancel()
        blinkJob?.cancel()
        nextPulseTickerJob?.cancel()
        pulseEventHideJob?.cancel()
        configServer.stop()
        stopPipeline(updateStatus = false)
        cameraExecutor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun recoverScheduleAfterUiExit() {
        if (pulseSessionClosing) return
        val now = System.currentTimeMillis()
        val nextAtMs = pulseScheduler.nextTriggerAtMs()
        val hasHealthySchedule = nextAtMs > (now + SCHEDULE_EXIT_RECOVERY_GRACE_MS)
        val activeCapture = running || pulseWindowJob != null || pulseEarlyCaptureJob != null
        if (hasHealthySchedule && !activeCapture) return

        val delay = when {
            isBatterySaverOn() -> POWER_SAVER_PULSE_INTERVAL_MS
            activeCapture -> RETRY_PULSE_INTERVAL_MS
            else -> DEFAULT_PULSE_INTERVAL_MS
        }
        pulseScheduler.scheduleNext(delay)
        PulseHudEventStore.publishStatus(this, "Scheduler recovered after HUD close")
        Log.i(TAG, "Scheduler recovered on activity stop/destroy. Next in ${delay / 1000}s")
    }

    private fun ensurePermissionsThenStart() {
        val missing = requiredPermissions().filterNot(::hasPermission)
        if (missing.isEmpty()) {
            startAfterPermission()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAfterPermission() {
        if (pulseModeLaunch) {
            queuePulseStart(trigger = "scheduled")
        } else {
            bootstrapScheduledMode()
        }
    }

    private fun queuePulseStart(trigger: String) {
        pendingPulseTrigger = trigger
        tryStartPendingPulse()
    }

    private fun tryStartPendingPulse() {
        val trigger = pendingPulseTrigger ?: return
        if (running || pulseWindowJob != null || pulseSessionClosing) return
        pendingPulseTrigger = null
        scope.launch {
            delay(PULSE_START_DELAY_MS)
            startPulseSession(trigger)
        }
    }

    private fun applyModeChrome() {
        toggleButton.text = "Snap"
        updateControlVisibility()
    }

    private fun updateControlVisibility() {
        val visible = if (!pulseModeLaunch && silencyMode.isMax()) View.VISIBLE else View.GONE
        toggleButton.visibility = visible
        silencyButton.visibility = visible
    }

    private fun applySilencyOverrideFromIntent(intent: Intent?) {
        val rawMode = intent?.getStringExtra(EXTRA_SILENCY_MODE)
        if (!rawMode.isNullOrBlank()) {
            silencyMode = SilencyMode.fromPersisted(rawMode.trim())
            GlassesSettingsStore.setSilencyMode(this, silencyMode)
            Log.i(TAG, "Silency override from intent: $rawMode -> ${silencyMode.persistedValue}")
        } else {
            silencyMode = GlassesSettingsStore.silencyMode(this)
            Log.i(TAG, "Silency mode from prefs: ${silencyMode.persistedValue}")
        }
        applySilencyUi()
    }

    private fun cycleSilencyMode() {
        silencyMode = silencyMode.next()
        GlassesSettingsStore.setSilencyMode(this, silencyMode)
        applySilencyUi()
        setStatus("Silency mode: ${silencyModeLabel(silencyMode)}")
    }

    private fun applySilencyUi() {
        when (silencyMode) {
            SilencyMode.HUD -> {
                pulseBadgeContainer.visibility = View.VISIBLE
                pulseBadgeContainer.alpha = 1f
                pulseBadgeText.visibility = View.VISIBLE
                nextPulseText.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.maxLines = 2
                verboseDetailText.visibility = View.GONE
                stealthIndicator.visibility = View.GONE
            }

            SilencyMode.COMPACT -> {
                pulseBadgeContainer.visibility = View.VISIBLE
                pulseBadgeContainer.alpha = 1f
                pulseBadgeText.visibility = View.VISIBLE
                nextPulseText.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.maxLines = 2
                verboseDetailText.visibility = View.VISIBLE
                stealthIndicator.visibility = View.GONE
            }

            SilencyMode.STEALTH -> {
                pulseBadgeContainer.visibility = View.GONE
                verboseDetailText.visibility = View.GONE
                stealthIndicator.visibility = View.VISIBLE
            }
        }
        silencyButton.text = "Verbosity: ${silencyModeLabel(silencyMode)}"
        updateControlVisibility()
        if (stealthIndicator.visibility == View.VISIBLE) {
            stealthIndicator.alpha = if (running) 1f else 0.35f
        } else {
            stealthIndicator.alpha = 0.35f
        }
        refreshVerboseDetail()
    }

    private fun bootstrapScheduledMode() {
        if (pulseScheduler.nextTriggerAtMs() <= System.currentTimeMillis()) {
            pulseScheduler.scheduleNext(DEFAULT_PULSE_INTERVAL_MS)
        }
        setPulseBadge(active = false, label = "SCHEDULED")
        refreshRestrictionStatus(force = true)
        refreshNextPulseLabel()
    }

    private fun requiredPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPulseSession(trigger: String) {
        if (running || pulseWindowJob != null || pulseSessionClosing) return
        if (isBatterySaverOn()) {
            pulseScheduler.scheduleNext(POWER_SAVER_PULSE_INTERVAL_MS)
            setPulseBadge(active = false, label = "DEFERRED")
            setStatus("Policy block: battery saver on")
            maybeCloseAfterPulse()
            return
        }

        running = true
        pulseSessionClosing = false
        frameTransferredThisSession = false
        analyzedFrames = 0
        lastStatusAtMs = 0L
        lastAnalyzeAtMs = 0L
        analyzeInFlight.set(false)

        setPulseBadge(active = true, label = "CAPTURING")
        startBlinkingIndicator()
        setStatus("Pulse $trigger started. Capturing for ${PULSE_WINDOW_MS / 1000}s")
        refreshNextPulseLabel()
        startPipeline()

        pulseWindowJob?.cancel()
        pulseEarlyCaptureJob?.cancel()
        pulseWindowJob = scope.launch {
            delay(PULSE_WINDOW_MS)
            if (!pulseSessionClosing) {
                val fallbackSent = if (!frameTransferredThisSession) {
                    runCatching { attemptImageCaptureTransfer() }.getOrDefault(false) ||
                        runCatching { attemptPreviewSnapshotTransfer() }.getOrDefault(false)
                } else {
                    false
                }
                val success = frameTransferredThisSession || fallbackSent
                endPulseSession(success = success, reason = if (success) "Pulse complete" else "No transferable frame")
            }
        }
    }

    private fun startPipeline() {
        if (!running) return
        setStatus("Starting camera + relay")

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image)
                }
                val useCases = mutableListOf<UseCase>(analysis, capture)
                // Some devices don't reliably deliver analysis frames without an active preview surface.
                val withPreview = true
                if (withPreview) {
                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    useCases += preview
                }

                val selector = resolveCameraSelector(provider)
                val boundCamera = provider.bindToLifecycle(
                    this,
                    selector,
                    *useCases.toTypedArray(),
                )
                val lensLabel = when (boundCamera.cameraInfo.lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "back"
                    CameraSelector.LENS_FACING_FRONT -> "front"
                    else -> "unknown"
                }
                Log.i(TAG, "Bound camera lens=$lensLabel")
                setStatus(
                    "Relay running ($lensLabel camera, ${if (withPreview) "preview+analysis" else "analysis-only"}). Guardrails: dark/dedup/battery/rate",
                )
                schedulePostBindCapture()
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun stopPipeline(updateStatus: Boolean = true) {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        imageCapture = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (updateStatus) {
            setStatus("Relay paused")
        }
    }

    private fun schedulePostBindCapture() {
        pulseEarlyCaptureJob?.cancel()
        pulseEarlyCaptureJob = scope.launch {
            delay(PULSE_POST_BIND_CAPTURE_MS)
            if (!running || frameTransferredThisSession || pulseSessionClosing) return@launch
            val sent = runCatching { attemptImageCaptureTransfer() }.getOrDefault(false)
            if (sent && !pulseSessionClosing) {
                endPulseSession(success = true, reason = "Frame transferred")
            }
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (!running || frameTransferredThisSession) return
            val now = System.currentTimeMillis()
            if (now - lastAnalyzeAtMs < PULSE_CAPTURE_INTERVAL_MS) return
            if (!analyzeInFlight.compareAndSet(false, true)) return
            lastAnalyzeAtMs = now

            analyzedFrames += 1
            if (analyzedFrames <= 5 || analyzedFrames % 15 == 0) {
                Log.i(TAG, "Analyzed $analyzedFrames frames")
            }

            val yData = image.yPlaneToPackedByteArray()
            if (yData.isEmpty()) {
                setStatusThrottled("Policy block: empty camera frame")
                return
            }

            val jpegRaw = yuv420ToJpeg(image, JPEG_QUALITY_CAPTURE)
            if (jpegRaw.isEmpty()) {
                setStatusThrottled("Policy block: JPEG conversion failed")
                return
            }

            val prepared = prepareFrameForSend(
                jpegRaw = jpegRaw,
                quality = JPEG_QUALITY,
                maxWidth = MAX_SEND_WIDTH,
                rotationDegrees = image.imageInfo.rotationDegrees,
            ) ?: run {
                setStatusThrottled("Policy block: JPEG prepare failed")
                return
            }

            val lumaMean = prepared.meanLuma
            val hash = prepared.hash
            val hashDelta = lastFrameHash?.let { hammingDistance(it, hash) } ?: 64
            val battery = batteryPercent()
            val charging = isCharging()

            val policyReason = when {
                battery in 0..10 && !charging -> "Policy block: battery ${battery}% and not charging"
                lumaMean < DARK_MEAN_THRESHOLD -> "Policy block: dark/black frame (mean=${"%.1f".format(Locale.US, lumaMean)})"
                hashDelta <= DEDUP_HAMMING_THRESHOLD -> "Policy block: dedup (hash delta=$hashDelta)"
                now - lastSentAtMs < MIN_SEND_INTERVAL_MS -> "Policy block: send rate limit"
                sendInFlight.get() -> "Policy block: previous send in-flight"
                else -> null
            }

            if (policyReason != null) {
                setStatusThrottled(policyReason)
                return
            }

            setStatus("Frame accepted. Sending to phone...")
            runOnUiThread {
                publishHudEvent(PulseHudEventType.CAPTURED)
            }
            sendInFlight.set(true)
            val frameId = "rokid_${now}"
            scope.launch(Dispatchers.IO) {
                val ack = runCatching {
                    sender.sendFrame(
                        frameId = frameId,
                        imageJpeg = prepared.jpeg,
                        lumaMean = lumaMean,
                        hashDelta = hashDelta,
                    )
                }.getOrElse { "send_failed: ${it.message}" }

                withContext(Dispatchers.Main) {
                    setStatus("Sent $frameId (${prepared.jpeg.size / 1024}KB) -> $ack")
                }
                if (!ack.startsWith("send_failed", ignoreCase = true)) {
                    lastFrameHash = hash
                    lastSentAtMs = System.currentTimeMillis()
                    frameTransferredThisSession = true
                    withContext(Dispatchers.Main) {
                        publishHudEvent(PulseHudEventType.SENT)
                    }
                    withContext(Dispatchers.Main) {
                        endPulseSession(success = true, reason = "Frame transferred")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        publishHudEvent(PulseHudEventType.FAILED)
                    }
                }
                sendInFlight.set(false)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "analyzeFrame failure", error)
        } finally {
            analyzeInFlight.set(false)
            image.close()
        }
    }

    private fun endPulseSession(success: Boolean, reason: String) {
        if (pulseSessionClosing) return
        pulseSessionClosing = true
        running = false
        pulseWindowJob?.cancel()
        pulseEarlyCaptureJob?.cancel()
        pulseWindowJob = null
        pulseEarlyCaptureJob = null
        stopPipeline(updateStatus = false)
        stopBlinkingIndicator()

        val nextDelay = when {
            isBatterySaverOn() -> POWER_SAVER_PULSE_INTERVAL_MS
            success -> if (batteryPercent() in 0..20) LOW_BATTERY_PULSE_INTERVAL_MS else DEFAULT_PULSE_INTERVAL_MS
            else -> RETRY_PULSE_INTERVAL_MS
        }
        pulseScheduler.scheduleNext(nextDelay)

        setPulseBadge(active = false, label = "SCHEDULED")
        setStatus(reason)
        refreshNextPulseLabel()

        if (closeAfterPulse) {
            maybeCloseAfterPulse()
        } else {
            toggleButton.isEnabled = true
            pulseModeLaunch = false
            applyModeChrome()
        }
    }

    private fun maybeCloseAfterPulse() {
        scope.launch {
            delay(700)
            pulseModeLaunch = false
            closeAfterPulse = false
            applyModeChrome()
            // Keep task in Recents so accidental closes don't kill scheduled pulse path.
            moveTaskToBack(true)
        }
    }

    private fun setPulseBadge(active: Boolean, label: String) {
        pulseBadgeText.text = label
        if (silencyMode.isSilent()) {
            pulseBlinkDot.alpha = 0.35f
            stealthIndicator.alpha = if (active) 1f else 0.35f
        } else {
            pulseBlinkDot.alpha = if (active) 1f else 0.35f
            stealthIndicator.alpha = 0.35f
        }
    }

    private fun startBlinkingIndicator() {
        blinkJob?.cancel()
        blinkJob = scope.launch {
            var visible = true
            while (running) {
                if (silencyMode.isSilent()) {
                    stealthIndicator.alpha = 1f
                    pulseBlinkDot.alpha = 0.35f
                } else {
                    pulseBlinkDot.alpha = if (visible) 1f else 0.25f
                    stealthIndicator.alpha = 0.35f
                    visible = !visible
                }
                delay(BLINK_INTERVAL_MS)
            }
            pulseBlinkDot.alpha = 0.35f
            stealthIndicator.alpha = 0.35f
        }
    }

    private fun stopBlinkingIndicator() {
        blinkJob?.cancel()
        blinkJob = null
        pulseBlinkDot.alpha = 0.35f
        stealthIndicator.alpha = 0.35f
    }

    private fun startNextPulseTicker() {
        nextPulseTickerJob?.cancel()
        nextPulseTickerJob = scope.launch {
            while (true) {
                refreshNextPulseLabel()
                refreshRestrictionStatus()
                ensureScheduleHealthy()
                renderLatestPulseStatus()
                renderLatestPulseEvent()
                delay(NEXT_LABEL_TICK_MS)
            }
        }
    }

    private fun refreshRestrictionStatus(force: Boolean = false) {
        if (running || pulseSessionClosing) return
        val current = statusText.text?.toString().orEmpty()
        if (isBackgroundRestricted()) {
            if (force || !current.contains("background-restricted", ignoreCase = true)) {
                setStatus("Policy block: app is background-restricted. Set Battery to Unrestricted for auto pulses.")
            }
            return
        }
        if (force || current.contains("background-restricted", ignoreCase = true)) {
            setStatus("Scheduler armed")
        }
    }

    private fun refreshNextPulseLabel() {
        val nextAtMs = pulseScheduler.nextTriggerAtMs()
        val label = when {
            running -> "Next capture: running..."
            pulseSessionClosing -> "Next capture: updating..."
            nextAtMs <= 0L -> "Next capture: --"
            else -> {
                val deltaSec = ((nextAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nextAtMs))
                "Next capture: $clock (${deltaSec}s)"
            }
        }
        runOnUiThread {
            nextPulseText.text = label
        }
        refreshVerboseDetail()
    }

    private fun ensureScheduleHealthy() {
        if (running || pulseSessionClosing) return
        val now = System.currentTimeMillis()
        val nextAtMs = pulseScheduler.nextTriggerAtMs()
        if (nextAtMs > 0L && nextAtMs >= (now - SCHEDULE_OVERDUE_GRACE_MS)) return
        if (now - lastScheduleRecoveryAtMs < SCHEDULE_RECOVERY_COOLDOWN_MS) return
        lastScheduleRecoveryAtMs = now
        pulseScheduler.scheduleNext(DEFAULT_PULSE_INTERVAL_MS)
        setStatus("Scheduler re-armed")
    }

    private fun publishHudEvent(type: PulseHudEventType) {
        lastPulseEventAtMs = PulseHudEventStore.publish(this, type)
        lastEventType = type
        showPulseEvent(type)
    }

    private fun renderLatestPulseStatus() {
        val latest = PulseHudEventStore.latestStatus(this) ?: return
        if (latest.atMs <= lastPulseStatusAtMs) return
        lastPulseStatusAtMs = latest.atMs
        setStatus(latest.text, publish = false)
    }

    private fun renderLatestPulseEvent() {
        val latest = PulseHudEventStore.latest(this) ?: return
        if (latest.atMs <= lastPulseEventAtMs) return
        lastPulseEventAtMs = latest.atMs
        lastEventType = latest.type
        showPulseEvent(latest.type)
    }

    private fun showPulseEvent(type: PulseHudEventType) {
        val (iconRes, tint) = when (type) {
            PulseHudEventType.CAPTURED -> android.R.drawable.ic_menu_camera to Color.parseColor("#77FF7A")
            PulseHudEventType.SENT -> android.R.drawable.sym_action_call to Color.parseColor("#62E6FF")
            PulseHudEventType.FAILED -> android.R.drawable.ic_delete to Color.parseColor("#FF6666")
        }
        pulseEventIcon.setImageResource(iconRes)
        pulseEventIcon.setColorFilter(tint)
        pulseEventContainer.visibility = View.VISIBLE
        pulseEventContainer.alpha = 1f
        pulseEventHideJob?.cancel()
        pulseEventHideJob = scope.launch {
            delay(HUD_EVENT_VISIBLE_MS)
            pulseEventContainer.visibility = View.GONE
            pulseEventContainer.alpha = 0f
        }
        refreshVerboseDetail()
    }

    private fun refreshVerboseDetail() {
        if (!silencyMode.isMax()) return
        val eventLabel = lastEventType?.name?.lowercase(Locale.US) ?: "none"
        val eventAt = if (lastPulseEventAtMs > 0L) {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastPulseEventAtMs))
        } else {
            "--:--:--"
        }
        val batteryLabel = batteryPercent().takeIf { it >= 0 }?.let { "$it%" } ?: "--"
        val state = when {
            running -> "capturing"
            pulseSessionClosing -> "closing"
            else -> "idle"
        }
        val statusSnippet = currentStatusText.take(20)
        val detail = "State:$state  Batt:$batteryLabel  Last:$eventLabel@$eventAt  Msg:$statusSnippet"
        runOnUiThread {
            verboseDetailText.text = detail
        }
    }

    private fun setStatus(text: String, publish: Boolean = true) {
        currentStatusText = text
        if (publish) {
            lastPulseStatusAtMs = PulseHudEventStore.publishStatus(this, text)
        }
        runOnUiThread {
            statusText.text = text
        }
        refreshVerboseDetail()
        Log.i(TAG, text)
    }

    private fun setStatusThrottled(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusAtMs < STATUS_THROTTLE_MS) return
        lastStatusAtMs = now
        setStatus(text)
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

    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isBackgroundRestricted == true
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

    private suspend fun attemptPreviewSnapshotTransfer(): Boolean {
        if (sendInFlight.get()) return false
        val snapshot = previewView.bitmap ?: run {
            setStatus("Policy block: preview snapshot unavailable")
            return false
        }
        if (snapshot.width <= 0 || snapshot.height <= 0) {
            snapshot.recycle()
            setStatus("Policy block: invalid preview snapshot size")
            return false
        }
        val prepared = runCatching {
            prepareBitmapForSend(snapshot, JPEG_QUALITY, MAX_SEND_WIDTH)
        }.getOrNull()
        snapshot.recycle()
        val frame = prepared ?: run {
            setStatus("Policy block: preview snapshot prepare failed")
            return false
        }

        val hashDelta = lastFrameHash?.let { hammingDistance(it, frame.hash) } ?: 64
        val battery = batteryPercent()
        val charging = isCharging()
        val now = System.currentTimeMillis()

        val policyReason = when {
            battery in 0..10 && !charging -> "Policy block: battery ${battery}% and not charging"
            frame.meanLuma < DARK_MEAN_THRESHOLD -> "Policy block: dark/black frame (mean=${"%.1f".format(Locale.US, frame.meanLuma)})"
            hashDelta <= DEDUP_HAMMING_THRESHOLD -> "Policy block: dedup (hash delta=$hashDelta)"
            now - lastSentAtMs < MIN_SEND_INTERVAL_MS -> "Policy block: send rate limit"
            else -> null
        }
        if (policyReason != null) {
            setStatus(policyReason)
            return false
        }

        setStatus("Preview fallback captured. Sending to phone...")
        publishHudEvent(PulseHudEventType.CAPTURED)
        sendInFlight.set(true)
        val frameId = "rokid_${now}_preview"
        val ack = withContext(Dispatchers.IO) {
            runCatching {
                sender.sendFrame(
                    frameId = frameId,
                    imageJpeg = frame.jpeg,
                    lumaMean = frame.meanLuma,
                    hashDelta = hashDelta,
                )
            }.getOrElse { "send_failed: ${it.message}" }
        }
        sendInFlight.set(false)

        setStatus("Preview fallback sent $frameId (${frame.jpeg.size / 1024}KB) -> $ack")
        if (ack.startsWith("send_failed", ignoreCase = true)) {
            publishHudEvent(PulseHudEventType.FAILED)
            return false
        }

        publishHudEvent(PulseHudEventType.SENT)
        lastFrameHash = frame.hash
        lastSentAtMs = System.currentTimeMillis()
        frameTransferredThisSession = true
        return true
    }

    private suspend fun attemptImageCaptureTransfer(): Boolean {
        if (sendInFlight.get()) return false
        val capture = imageCapture ?: run {
            setStatus("Policy block: image capture unavailable")
            return false
        }
        val captured = captureStillJpeg(capture) ?: run {
            setStatus("Policy block: image capture failed")
            return false
        }
        val prepared = runCatching {
            prepareFrameForSend(
                jpegRaw = captured.jpeg,
                quality = JPEG_QUALITY,
                maxWidth = MAX_SEND_WIDTH,
                rotationDegrees = captured.rotationDegrees,
            )
        }.getOrNull() ?: run {
            setStatus("Policy block: image capture prepare failed")
            return false
        }

        val hashDelta = lastFrameHash?.let { hammingDistance(it, prepared.hash) } ?: 64
        val battery = batteryPercent()
        val charging = isCharging()
        val now = System.currentTimeMillis()

        val policyReason = when {
            battery in 0..10 && !charging -> "Policy block: battery ${battery}% and not charging"
            prepared.meanLuma < DARK_MEAN_THRESHOLD -> "Policy block: dark/black frame (mean=${"%.1f".format(Locale.US, prepared.meanLuma)})"
            hashDelta <= DEDUP_HAMMING_THRESHOLD -> "Policy block: dedup (hash delta=$hashDelta)"
            now - lastSentAtMs < MIN_SEND_INTERVAL_MS -> "Policy block: send rate limit"
            else -> null
        }
        if (policyReason != null) {
            setStatus(policyReason)
            return false
        }

        setStatus("ImageCapture fallback captured. Sending to phone...")
        publishHudEvent(PulseHudEventType.CAPTURED)
        sendInFlight.set(true)
        val frameId = "rokid_${now}_capture"
        val ack = withContext(Dispatchers.IO) {
            runCatching {
                sender.sendFrame(
                    frameId = frameId,
                    imageJpeg = prepared.jpeg,
                    lumaMean = prepared.meanLuma,
                    hashDelta = hashDelta,
                )
            }.getOrElse { "send_failed: ${it.message}" }
        }
        sendInFlight.set(false)

        setStatus("ImageCapture fallback sent $frameId (${prepared.jpeg.size / 1024}KB) -> $ack")
        if (ack.startsWith("send_failed", ignoreCase = true)) {
            publishHudEvent(PulseHudEventType.FAILED)
            return false
        }

        publishHudEvent(PulseHudEventType.SENT)
        lastFrameHash = prepared.hash
        lastSentAtMs = System.currentTimeMillis()
        frameTransferredThisSession = true
        return true
    }

    private suspend fun captureStillJpeg(capture: ImageCapture): CapturedJpeg? {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            runCatching {
                capture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val jpegRaw = yuv420ToJpeg(image, JPEG_QUALITY_CAPTURE)
                                val rotation = image.imageInfo.rotationDegrees
                                if (!continuation.isActive) return
                                if (jpegRaw.isNotEmpty()) {
                                    continuation.resume(CapturedJpeg(jpegRaw, rotation))
                                } else {
                                    continuation.resume(null)
                                }
                            } catch (error: Throwable) {
                                Log.e(TAG, "captureStillJpeg conversion failure", error)
                                if (continuation.isActive) continuation.resume(null)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "captureStillJpeg failure", exception)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }.onFailure { error ->
                Log.e(TAG, "captureStillJpeg launch failure", error)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun prepareBitmapForSend(
        bitmap: Bitmap,
        quality: Int,
        maxWidth: Int,
    ): PreparedFrame? {
        val scaled = if (bitmap.width > maxWidth) {
            val outWidth = maxWidth.coerceAtLeast(1)
            val outHeight = (bitmap.height.toFloat() * (outWidth.toFloat() / bitmap.width.toFloat()))
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true)
        } else {
            bitmap
        }
        val meanLuma = sampledLumaMean(scaled)
        val hash = frameHash(scaled)
        val preparedJpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        if (preparedJpeg.isEmpty()) return null
        return PreparedFrame(
            jpeg = preparedJpeg,
            meanLuma = meanLuma,
            hash = hash,
        )
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

    private fun resolveCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        val backAvailable = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        if (backAvailable) return CameraSelector.DEFAULT_BACK_CAMERA

        val frontAvailable = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (frontAvailable) return CameraSelector.DEFAULT_FRONT_CAMERA

        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    companion object {
        private const val TAG = "RokidFrameRelay"
        const val ACTION_PULSE_CAPTURE = "com.rokid.context.glasses.action.PULSE_CAPTURE"
        const val EXTRA_SILENCY_MODE = "silency_mode"

        private const val DEFAULT_PULSE_INTERVAL_MS = 30_000L
        private const val LOW_BATTERY_PULSE_INTERVAL_MS = 60_000L
        private const val POWER_SAVER_PULSE_INTERVAL_MS = 60_000L
        private const val RETRY_PULSE_INTERVAL_MS = 15_000L
        private const val PULSE_WINDOW_MS = 12_000L
        private const val PULSE_POST_BIND_CAPTURE_MS = 1_500L
        private const val PULSE_START_DELAY_MS = 700L
        private const val PULSE_CAPTURE_INTERVAL_MS = 1_200L
        private const val BLINK_INTERVAL_MS = 380L
        private const val MIN_SEND_INTERVAL_MS = 2_500L
        private const val STATUS_THROTTLE_MS = 2_000L
        private const val NEXT_LABEL_TICK_MS = 1_000L
        private const val HUD_EVENT_VISIBLE_MS = 1_200L
        private const val SCHEDULE_OVERDUE_GRACE_MS = 5_000L
        private const val SCHEDULE_RECOVERY_COOLDOWN_MS = 10_000L
        private const val SCHEDULE_EXIT_RECOVERY_GRACE_MS = 2_000L
        private const val DARK_MEAN_THRESHOLD = 8.0
        private const val DEDUP_HAMMING_THRESHOLD = 4
        private const val JPEG_QUALITY_CAPTURE = 72
        private const val JPEG_QUALITY = 62
        private const val MAX_SEND_WIDTH = 360

        private fun silencyModeLabel(mode: SilencyMode): String {
            return when (mode) {
                SilencyMode.HUD -> "MEDIUM"
                SilencyMode.COMPACT -> "MAX"
                SilencyMode.STEALTH -> "SILENT"
            }
        }
    }

    private data class PreparedFrame(
        val jpeg: ByteArray,
        val meanLuma: Double,
        val hash: Long,
    )

    private data class CapturedJpeg(
        val jpeg: ByteArray,
        val rotationDegrees: Int,
    )
}

private class BluetoothFrameSender(
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
                .put("meta", JSONObject()
                    .put("compressed_on", "glasses")
                    .put("luma_mean", lumaMean)
                    .put("hash_delta", hashDelta)
                    .put("size_bytes", imageJpeg.size)
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
