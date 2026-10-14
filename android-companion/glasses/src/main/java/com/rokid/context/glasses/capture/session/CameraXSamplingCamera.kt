package com.rokid.context.glasses.capture.session

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
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

    fun analysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolution)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    fun selector(provider: ProcessCameraProvider): CameraSelector {
        if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) return CameraSelector.DEFAULT_BACK_CAMERA
        if (runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) return CameraSelector.DEFAULT_FRONT_CAMERA
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * Binds [analysis] (and a preview onto a throwaway texture, which this camera HAL needs to deliver frames) to [owner].
     * Main thread only. The result unbinds and releases the texture exactly once; null when the bind threw.
     */
    fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider, analysis: ImageAnalysis, executor: Executor): CaptureSession? {
        lateinit var texture: SurfaceTexture
        val session = CaptureSession(unbind = { provider.unbindAll() }, release = { texture.release() }).also { texture = SurfaceTexture(0) }
        val preview = Preview.Builder().setResolutionSelector(resolution).build().also { p ->
            p.setSurfaceProvider { request ->
                texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(texture)
                request.provideSurface(surface, executor) { runCatching { surface.release() }; runCatching { texture.release() } }
            }
        }
        return try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector(provider), analysis, preview)
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

    override suspend fun open(onFrame: (FrameSource) -> Unit): Boolean {
        val p = provider ?: CameraXBind.provider(context)?.also { provider = it } ?: return false
        val analysis = CameraXBind.analysis()
        analysis.setAnalyzer(executor) { image ->
            try { onFrame(ImageProxyFrame(image)) } catch (e: Exception) { Log.w(TAG, "analyze failed: ${e.javaClass.simpleName}") } finally { image.close() }
        }
        held = CameraXBind.bind(owner, p, analysis, executor)
        return held != null
    }

    override fun close() {
        val h = held ?: return
        held = null
        if (Looper.myLooper() == Looper.getMainLooper()) h.close() else Handler(Looper.getMainLooper()).post { h.close() }
    }

    private companion object { const val TAG = "PcCapture" }
}
