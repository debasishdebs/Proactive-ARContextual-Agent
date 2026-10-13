package com.rokid.context.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rokid.context.core.hud.render.HudCopy
import com.rokid.context.core.hud.render.HudModel
import com.rokid.context.core.hud.render.HudNav
import com.rokid.context.core.hud.render.HudPresenter
import com.rokid.context.core.hud.render.HudRoot
import com.rokid.context.core.hud.render.LocalOverlay
import com.rokid.context.core.hud.render.pagesFor
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.Overlay
import com.rokid.context.glasses.capture.CaptureScheduler
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.hud.ConfirmInput
import com.rokid.context.glasses.hud.InputRouter
import com.rokid.context.glasses.hud.KeyGestures
import com.rokid.context.glasses.hud.KeyResult
import com.rokid.context.glasses.hud.PadInput
import com.rokid.context.glasses.hud.Routed
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.link.HudSettingsCache
import com.rokid.context.glasses.pause.PauseToggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glasses HUD (spec §3.1), adapted from Live Fit `MainActivity`: one activity, screen kept on, [HudRoot] drawn from
 * the phone's StateFrame plus local state, touchpad keys routed through [KeyGestures] and [InputRouter]. Its lifetime
 * is the CXR session's: destroying it closes the phone's CUSTOM_APP session (spec §1.7).
 */
class MainActivity : ComponentActivity() {
    private val runtime by lazy { GlassesRuntime.get(this) }
    internal lateinit var controller: HudController
        private set
    private val keys = KeyGestures()
    private val swipeTimer = Handler(Looper.getMainLooper())
    private val closeSwipe = Runnable { handle(keys.onTimer(SystemClock.uptimeMillis())); scheduleSwipeClose() }
    private val presenter = HudPresenter()
    private val confirm = ConfirmInput()
    private var nav by mutableStateOf(HudNav())
    private var highlightYes by mutableStateOf(true)
    private var local by mutableStateOf<LocalOverlay?>(null)
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureCaptureScheduled() else Log.w(TAG, "camera permission denied: capture stays off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = HudController(
            lifecycleScope, runtime.bridge, HudSettingsCache(getSharedPreferences(HudSettingsCache.PREFS, MODE_PRIVATE)),
            hello = { runtime.hello(nextCaptureAt()) },
        )
        runtime.attach(controller)
        controller.onActivityStart()
        controller.start()
        lifecycleScope.launch { runtime.pauses.ensureLoaded() }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ensureCaptureScheduled()
        else cameraPermission.launch(Manifest.permission.CAMERA)
        if (savedInstanceState == null) handleIntent(intent) // a recreated activity must not capture again
        setContent { Hud() }
    }

    /** (Re)started: phone messages may open the CXR session again (spec §1.7 send gate). */
    override fun onStart() {
        super.onStart()
        controller.onActivityStart()
        runtime.hudPresence.onStarted()
    }

    /** Finishing closes the session at once; [onDestroy] may come late. */
    override fun onStop() {
        if (isFinishing) controller.onActivityExit()
        runtime.hudPresence.onStopped(finishing = isFinishing)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Composable
    private fun Hud() {
        val frame by controller.frame.collectAsStateWithLifecycle()
        val settings by controller.settings.collectAsStateWithLifecycle()
        val connection by controller.connection.collectAsStateWithLifecycle()
        val pauses by runtime.pauses.records.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var battery by remember { mutableStateOf(runtime.batteryPct()) }
        LaunchedEffect(Unit) {
            var ticks = 0
            while (true) {
                delay(1_000)
                now = System.currentTimeMillis()
                nav = nav.tick(now)
                if (++ticks % 30 == 0) battery = runtime.batteryPct()
            }
        }
        val todayRows = frame?.today?.size ?: 0
        LaunchedEffect(settings.verbosity, todayRows) { nav = nav.reconcile(pagesFor(settings.verbosity), todayRows) }
        val view = presenter.present(frame, settings.verbosity, nav, local, now)
        // Votes follow the presented overlay: an expired, hidden or dismissed Confirm can never be answered unseen.
        val shownConfirm = view.overlay as? Overlay.Confirm
        LaunchedEffect(shownConfirm?.proposalId) { confirm.onConfirm(shownConfirm); highlightYes = confirm.highlightYes }
        HudRoot(
            HudModel(
                view = view, frame = frame, settings = settings,
                connection = connection, batteryPct = battery, paused = pauses.any { it.isActiveAt(now) },
                nextCaptureAtMs = nextCaptureAt(), nowMs = now, nav = nav, confirmHighlightYes = highlightYes,
            ),
        )
    }

    /** Touchpad keys are ours: KEYCODE_NOTIFICATION must never reach the system, or it moves our task to the back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val r = when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.onKeyDown(event.keyCode, event.repeatCount, event.eventTime, System.currentTimeMillis())
            KeyEvent.ACTION_UP -> keys.onKeyUp(event.keyCode, event.eventTime)
            else -> KeyResult(consumed = false)
        }
        if (!r.consumed) return super.dispatchKeyEvent(event)
        handle(r.inputs)
        scheduleSwipeClose()
        return true
    }

    /** BACK is a double-tap on some firmware (Live Fit); never page navigation, never "exit". */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() = handle(keys.onBack(System.currentTimeMillis()))

    private fun scheduleSwipeClose() {
        swipeTimer.removeCallbacks(closeSwipe)
        keys.deadlineMs?.let { swipeTimer.postAtTime(closeSwipe, it) } // KeyEvent.eventTime and postAtTime share the uptime clock
    }

    private fun handle(inputs: List<PadInput>) {
        for (input in inputs) {
            val now = System.currentTimeMillis()
            val shown = presenter.present(controller.frame.value, controller.settings.value.verbosity, nav, local, now).overlay
            confirm.onConfirm(shown as? Overlay.Confirm)
            highlightYes = confirm.highlightYes
            when (val r = InputRouter.route(input, shown, controller.settings.value.gestures)) {
                Routed.ConfirmToggle -> { confirm.onSwipe(); highlightYes = confirm.highlightYes }
                Routed.ConfirmChoose -> confirm.onTap()?.let { controller.send(it); presenter.dismiss(shown) }
                Routed.ConfirmNo -> confirm.onNo()?.let { controller.send(it); presenter.dismiss(shown) }
                Routed.CancelListening -> controller.send(Command.Cancel)
                Routed.DismissOverlay -> presenter.dismiss(shown)
                is Routed.Mapped -> onAction(r, shown, now)
            }
            nav = nav.touched(now)
        }
    }

    private fun onAction(r: Routed.Mapped, shown: Overlay?, now: Long) {
        val settings = controller.settings.value
        when (r.action) {
            GestureAction.NEXT_PAGE, GestureAction.PREV_PAGE -> {
                val next = nav.onSwipeAction(r.action, r.long, pagesFor(settings.verbosity), controller.frame.value?.today?.size ?: 0, now)
                if (next.page != nav.page) controller.send(Command.PageChanged(next.page))
                nav = next
            }
            // Push-to-talk arrives in Phase 6; until then a tap gives feedback instead of silence (spec §3.6 offline toast).
            GestureAction.ASK -> local = LocalOverlay.Toast(
                HudCopy.askUnavailable(controller.connection.value, controller.settings.value.askBlockedReason) ?: "Ask coming soon", now,
            )
            GestureAction.PAUSE_RESUME -> lifecycleScope.launch {
                val t = try {
                    runtime.togglePause()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pause toggle failed: ${e.javaClass.simpleName}")
                    return@launch
                }
                onPauseToggle(t, now)
            }
            GestureAction.DISMISS -> { presenter.dismiss(shown); local = null }
            GestureAction.NONE -> Unit
        }
    }

    /**
     * Applied on the glasses first (spec §5.5); the phone hears it now, or from `pc_hello` after a reconnect: a Resume
     * stays in every hello's `resumedAtMs` until the phone stops listing the ids, since a send result of 0 is not delivery.
     */
    private fun onPauseToggle(t: PauseToggle, now: Long) {
        when (t) {
            is PauseToggle.Paused -> {
                controller.send(Command.Pause(t.record))
                local = LocalOverlay.Splash(now)
                Log.i(TAG, "paused locally ${t.record.pauseId}")
            }
            is PauseToggle.Resumed -> {
                controller.send(Command.Resume(t.clears))
                local = LocalOverlay.Toast("Capture resumed", now)
                Log.i(TAG, "resumed ${t.clears.size} pause(s)")
            }
        }
    }

    // CaptureService is the only capture path (spec §3.1); its scheduler drives the Glance countdown.
    private fun nextCaptureAt(): Long? = CaptureScheduler(this).nextTriggerAtMs()

    private fun ensureCaptureScheduled() = CaptureScheduler(this).ensureScheduled()

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != ACTION_CAPTURE_NOW) return
        CaptureService.captureNow(this, skipDedupe = i.getBooleanExtra(CaptureService.EXTRA_SKIP_DEDUPE, false))
    }

    override fun onDestroy() {
        swipeTimer.removeCallbacks(closeSwipe)
        controller.onActivityExit()
        if (isFinishing) runtime.hudPresence.onExited()
        runtime.detach(controller)
        super.onDestroy()
    }

    companion object {
        const val TAG = "PcGlasses"
        /** adb and foreground-fallback entry: capture one frame now. */
        const val ACTION_CAPTURE_NOW = "com.rokid.context.glasses.action.CAPTURE_NOW"
    }
}
