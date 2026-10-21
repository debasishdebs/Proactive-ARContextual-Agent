package com.rokid.context.glasses.capture.session

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rokid.context.glasses.capture.CaptureSession
import com.rokid.context.glasses.capture.diag.DiagLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * CameraX plumbing shared by the sampling session and the Fixed one-shot (both bind through [com.rokid.context.glasses.capture.session.CameraOwner]).
 * The camera LED is the platform's: nothing here touches torch, flash or LED (see LedSourceRulesTest).
 */
internal object CameraXBind {
    private const val TAG = "PcCapture"

    /** 4:3 at 640x480 (landscape after rotation); the keyframe width is checked again after rotation (spec P1.1). */
    val resolution: ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

    suspend fun provider(context: Context): ProcessCameraProvider? = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { val p = runCatching { future.get() }.getOrNull(); if (continuation.isActive) continuation.resume(p) },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** [fps] caps the stream through the AE target range; null keeps the HAL default. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun analysis(fps: IntRange? = null): ImageAnalysis {
        val b = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (fps != null) Camera2Interop.Extender(b).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.first, fps.last))
        return b.build()
    }

    private var fpsProvider: ProcessCameraProvider? = null
    private val fpsCache = FpsRangePick.Cache {
        val provider = fpsProvider ?: return@Cache null
        runCatching { readFpsRanges(provider) }.getOrNull()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readFpsRanges(provider: ProcessCameraProvider): List<IntRange>? {
        val info = selector(provider).filter(provider.availableCameraInfos).firstOrNull() ?: return null
        return Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
    }

    /** The capped fps range for the selected camera, read once per process (main thread, before building the use cases); a failed or empty read is not cached (A23). */
    fun fpsRange(provider: ProcessCameraProvider): IntRange? {
        fpsProvider = provider
        val was = fpsCache.isResolved
        val r = fpsCache.get()
        if (!was && fpsCache.isResolved) DiagLog.i(TAG, "camera fps range=" + (r?.let { "${it.first}-${it.last}" } ?: "default"))
        return r
    }

    fun selector(provider: ProcessCameraProvider): CameraSelector {
        if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) return CameraSelector.DEFAULT_BACK_CAMERA
        if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) return CameraSelector.DEFAULT_FRONT_CAMERA
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * Binds [analysis] (and a preview onto a throwaway texture, which this camera HAL needs to deliver frames) to [owner].
     * Main thread only. The result unbinds and releases the texture exactly once; null when the bind threw.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun bind(
        owner: LifecycleOwner, provider: ProcessCameraProvider, analysis: ImageAnalysis, executor: Executor,
        fps: IntRange? = null, onBound: ((androidx.camera.core.Camera) -> Unit)? = null,
    ): CaptureSession? {
        lateinit var texture: SurfaceTexture
        val session = CaptureSession(unbind = { provider.unbindAll() }, release = { texture.release() }).also { texture = SurfaceTexture(0) }
        val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
        if (fps != null) Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.first, fps.last))
        val preview = previewBuilder.build().also { p ->
            p.setSurfaceProvider { request ->
                texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(texture)
                request.provideSurface(surface, executor) { runCatching { surface.release() }; runCatching { texture.release() } }
            }
        }
        return try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, selector(provider), analysis, preview)
            onBound?.invoke(camera)
            session
        } catch (e: Exception) {
            Log.w(TAG, "camera bind failed: ${e.javaClass.simpleName}")
            session.close()
            null
        }
    }
}

/** An [ImageProxy] as a [FrameSource]: [copy] reads the planes now, before the analyzer returns and the proxy closes. */
internal class ImageProxyFrame(private val image: ImageProxy) : FrameSource {
    override fun copy(): FrameCopy? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3 || image.width <= 0 || image.height <= 0) return null
        val p = image.planes
        val thumb = YuvCopy.thumb(p[0].buffer, p[0].rowStride, p[0].pixelStride, image.width, image.height)
        val nv21 = YuvCopy.nv21(
            p[0].buffer, p[0].rowStride, p[0].pixelStride, p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride, image.width, image.height, image.imageInfo.rotationDegrees,
        )
        return FrameCopy(thumb, nv21)
    }
}

/**
 * The sampling session's camera: each [open] binds a fresh 640x480 analysis (keep-only-latest), [close] unbinds.
 * [open] and [close] run on the session's scope, the main thread in the service.
 */
internal class CameraXSamplingCamera(private val context: Context, private val owner: LifecycleOwner, private val executor: Executor) : SamplingCamera {
    private var provider: ProcessCameraProvider? = null
    @Volatile private var held: CaptureSession? = null
    @Volatile override var busy = false
        private set
    private var watch: Pair<androidx.lifecycle.LiveData<androidx.camera.core.CameraState>, androidx.lifecycle.Observer<androidx.camera.core.CameraState>>? = null

    override suspend fun open(onFrame: (FrameSource) -> Unit): Boolean {
        val p = provider ?: CameraXBind.provider(context)?.also { provider = it } ?: return false
        val fps = CameraXBind.fpsRange(p)
        val analysis = CameraXBind.analysis(fps)
        analysis.setAnalyzer(executor) { image ->
            try { onFrame(ImageProxyFrame(image)) } catch (e: Exception) { Log.w(TAG, "analyze failed: ${e.javaClass.simpleName}") } finally { image.close() }
        }
        busy = false
        held = CameraXBind.bind(owner, p, analysis, executor, fps = fps) { cam ->
            // Another app holding the camera shows as a CameraState error, not as a frame: remember it (main thread).
            val live = cam.cameraInfo.cameraState
            val obs = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { st ->
                val code = st.error?.code
                if (code == androidx.camera.core.CameraState.ERROR_CAMERA_IN_USE || code == androidx.camera.core.CameraState.ERROR_MAX_CAMERAS_IN_USE) busy = true
            }
            live.observeForever(obs)
            watch = live to obs
        }
        return held != null
    }

    override fun close() {
        val h = held ?: return
        held = null
        val w = watch
        watch = null
        val release = { w?.let { (live, obs) -> live.removeObserver(obs) }; h.close() }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else Handler(Looper.getMainLooper()).post { release() }
    }

    private companion object { const val TAG = "PcCapture" }
}
