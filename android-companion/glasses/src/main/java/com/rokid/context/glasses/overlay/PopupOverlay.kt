package com.rokid.context.glasses.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import com.rokid.context.core.log.PcLog
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.Verbosity

/**
 * The minimised pop-up (spec 2026-10-14 §4): one `TYPE_APPLICATION_OVERLAY` window, not focusable, not touchable,
 * top-center, at most 3 lines, green on black. A newer [show] replaces the text and restarts the timer: never stacked.
 * Process-wide (held by GlassesRuntime).
 */
class PopupOverlay(private val context: Context, private val windowManager: WindowManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }
    private var view: TextView? = null
    /** The text shown now, null when hidden. */
    var text: String? = null
        private set

    fun show(text: String, durationMs: Long) {
        val v = view ?: TextView(context).also { nv ->
            nv.setTextColor(GREEN)
            nv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            nv.maxLines = MAX_LINES
            nv.ellipsize = TextUtils.TruncateAt.END
            val d = context.resources.displayMetrics.density
            nv.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            nv.maxWidth = (context.resources.displayMetrics.widthPixels * 0.85f).toInt()
            nv.background = GradientDrawable().apply { setColor(Color.BLACK); setStroke((2 * d).toInt(), GREEN); cornerRadius = 14 * d }
            val added = runCatching { windowManager.addView(nv, layoutParams()) }
                .onFailure { PcLog.w(TAG, "pop-up window refused: ${it.javaClass.simpleName}") }.isSuccess
            if (!added) return
            view = nv
        }
        v.text = text
        this.text = text
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, durationMs)
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        text = null
        val v = view ?: return
        view = null
        runCatching { windowManager.removeView(v) }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = (24 * context.resources.displayMetrics.density).toInt()
        title = WINDOW_TITLE
    }

    companion object {
        const val TAG = "PcGlasses"
        const val WINDOW_TITLE = "PcPopup"
        const val MAX_LINES = 3
        private val GREEN = Color.rgb(0x3C, 0xFF, 0x6E)

        /**
         * What a phone overlay becomes while minimised: pop-up text, or null. Listening never happens minimised and a
         * Confirm opens the HUD instead ([MainActivity]); a Nudge shows unless the wearer chose Silent.
         */
        fun textFor(o: Overlay?, verbosity: Verbosity): String? = when (o) {
            is Overlay.Answer -> o.text.ifBlank { o.question }.ifBlank { null }
            is Overlay.Nudge -> o.text.takeIf { verbosity != Verbosity.SILENT }
            is Overlay.Toast -> o.text
            is Overlay.Confirm, is Overlay.Listening, Overlay.PausedSplash, null -> null
        }
    }
}
