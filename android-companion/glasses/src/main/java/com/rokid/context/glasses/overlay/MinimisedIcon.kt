package com.rokid.context.glasses.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.rokid.context.core.log.PcLog
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rokid.context.core.model.IconCorner

/**
 * What the icon draws: [fraction] of the countdown left (null = unknown, full ring), [paused] = glyph, no ring,
 * [cue] = a frame was just sent (PhotoCue): the whole ring lights, bolder, for about a second.
 */
data class IconState(val running: Boolean, val fraction: Float?, val paused: Boolean, val cue: Boolean = false)

/** Pure: the ring's share of the wait to the next photo, 1 just after arming, 0 when due. */
object IconRing {
    fun fraction(nextAtMs: Long?, armedAtMs: Long?, nowMs: Long): Float? {
        if (nextAtMs == null || armedAtMs == null || nextAtMs <= armedAtMs) return null
        return ((nextAtMs - nowMs).toFloat() / (nextAtMs - armedAtMs)).coerceIn(0f, 1f)
    }
}

/**
 * The minimised icon (spec 2026-10-14 §3): one `TYPE_APPLICATION_OVERLAY` window, not focusable, not touchable, so the
 * front app keeps the touchpad. A 48 dp circle: bright ring on black (black is see-through on the Rokid display), the
 * ring shrinking to the next capture; a pause glyph when paused. Process-wide (held by GlassesRuntime): an activity
 * recreation never adds a second window. Ticks once a second from [source] while shown; hides itself when capture
 * (the keep-alive service) is no longer running.
 */
class MinimisedIcon(
    private val context: Context,
    private val windowManager: WindowManager,
    private val source: () -> IconState = { IconState(running = true, fraction = null, paused = false) },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val s = source()
            if (!s.running) { hide(); return }
            update(s.fraction, s.paused, s.cue)
            handler.postDelayed(this, TICK_MS)
        }
    }
    internal var view: RingView? = null
        private set
    internal var params: WindowManager.LayoutParams? = null
        private set
    val isShown: Boolean get() = view != null

    /** Adds the window (or moves the one shown) to [corner]; false when the platform refused it. */
    fun show(corner: IconCorner): Boolean {
        val lp = layoutParams(corner)
        val v = view
        if (v != null) {
            params = lp
            runCatching { windowManager.updateViewLayout(v, lp) }
        } else {
            val nv = RingView(context)
            val added = runCatching { windowManager.addView(nv, lp) }
                .onFailure { PcLog.w(TAG, "icon window refused: ${it.javaClass.simpleName}") }.isSuccess
            if (!added) return false
            view = nv
            params = lp
        }
        handler.removeCallbacks(tick)
        tick.run()
        return view != null
    }

    fun update(fractionRemaining: Float?, paused: Boolean, cue: Boolean = false) {
        view?.let { it.fraction = fractionRemaining; it.paused = paused; it.pulse = cue; it.invalidate() }
    }

    /** Re-reads [source] now (on Main) instead of at the next 1 s tick; nothing when the icon is hidden. */
    fun refresh() {
        handler.post {
            if (view == null) return@post
            handler.removeCallbacks(tick)
            tick.run()
        }
    }

    fun hide() {
        handler.removeCallbacks(tick)
        val v = view ?: return
        view = null
        params = null
        runCatching { windowManager.removeView(v) }
    }

    private fun layoutParams(corner: IconCorner): WindowManager.LayoutParams {
        val px = (SIZE_DP * context.resources.displayMetrics.density).toInt()
        val margin = (MARGIN_DP * context.resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            px, px, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (corner) {
                IconCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
                IconCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                IconCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                IconCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            x = margin
            y = margin
            title = "PcMinimisedIcon"
        }
    }

    /** Ring + glyph, drawn on a canvas (no Compose: an overlay window has no lifecycle owner). */
    internal class RingView(context: Context) : View(context) {
        var fraction: Float? = null
        var paused: Boolean = false
        /** The "photo sent" pulse: full ring, thicker stroke, bigger centre dot. */
        var pulse: Boolean = false
        private val green = Color.rgb(0x3C, 0xFF, 0x6E)
        private val stroke = 3f * context.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = green; strokeCap = Paint.Cap.ROUND }
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = green; alpha = 90 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = green }
        private val box = RectF()

        /** The arc's sweep in degrees (0 when paused); for tests. */
        val sweepDegrees: Float get() = when { paused -> 0f; pulse -> 360f; else -> 360f * (fraction ?: 1f) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            box.set(stroke, stroke, w - stroke, h - stroke)
            if (paused) {
                val bw = w * 0.12f; val bh = h * 0.4f; val top = (h - bh) / 2
                canvas.drawRect(w * 0.34f, top, w * 0.34f + bw, top + bh, fill)
                canvas.drawRect(w * 0.54f, top, w * 0.54f + bw, top + bh, fill)
                return
            }
            canvas.drawOval(box, dim)
            ring.strokeWidth = if (pulse) stroke * 1.8f else stroke
            canvas.drawArc(box, -90f, sweepDegrees, false, ring)
            canvas.drawCircle(w / 2, h / 2, if (pulse) w * 0.18f else w * 0.1f, fill)
        }
    }

    companion object {
        const val TAG = "PcGlasses"
        const val SIZE_DP = 48
        const val MARGIN_DP = 16
        const val TICK_MS = 1_000L
    }
}
