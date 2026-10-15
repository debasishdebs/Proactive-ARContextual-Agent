package com.rokid.context.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rokid.context.core.hud.DoubleTapExit
import com.rokid.context.core.hud.ExitGate
import com.rokid.context.core.hud.PageAction
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
import com.rokid.context.glasses.hud.CaptureCueMark
import com.rokid.context.glasses.hud.CaptureStatusMarks
import com.rokid.context.glasses.hud.ConfirmInput
import com.rokid.context.glasses.hud.InputRouter
import com.rokid.context.glasses.hud.KeyGestures
import com.rokid.context.glasses.hud.KeyResult
import com.rokid.context.glasses.hud.PadInput
import com.rokid.context.glasses.hud.PhoneAskWatch
import com.rokid.context.glasses.hud.Routed
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.overlay.PopupOverlay
import com.rokid.context.core.model.popupMs
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.voice.PttProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
    private val askWatch = PhoneAskWatch()
    private var nav by mutableStateOf(HudNav())
    private var highlightYes by mutableStateOf(true)
    private var local by mutableStateOf<LocalOverlay?>(null)
    /** The double-tap exit gate (spec 2026-10-14 §2), on the uptime clock; [gateTick] runs while it is armed. */
    private var exitGate: ExitGate = ExitGate.Idle
    private val gateTick = Runnable { tickGate() }
    /** Spec 2026-10-14 §4: the HUD was opened for a Confirm and goes back to the background after it. */
    private var returnToMinimised = false
    private var autoOpenedAtMs = 0L
    /** The proposal the HUD was opened for (and the last one launched, so a repeat frame never opens it twice). */
    private var autoConfirmId: String? = null
    private var launchedConfirmId: String? = null
    private val confirmTimeout = Runnable { afterConfirm() }
    /** The last phone overlay seen, so a heartbeat repeat never re-pops. */
    private var lastOverlay: Overlay? = null
    /** Minimised when this start began, until the next intent decides why we came forward. */
    private var wasMinimised = false
    private var sessionReopenedAtMs = Long.MIN_VALUE / 2
    /** A permission request is on screen: Android drops a second one, so none is launched until it answers. */
    private var permissionPending = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionPending = false
        if (result[Manifest.permission.CAMERA] == true) { ensureCaptureScheduled(); CaptureService.startKeepAlive(this) }
        else if (!granted(Manifest.permission.CAMERA)) Log.w(TAG, "camera permission denied: capture stays off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = HudController(
            lifecycleScope, runtime.bridge, runtime.settingsCache, // the one cache, so the capture service sees each push at once
            hello = { runtime.hello(nextCaptureAt()) },
        )
        runtime.attach(controller)
        runtime.ptt.onProblem = { p -> runOnUiThread { local = LocalOverlay.Toast(pttText(p), System.currentTimeMillis()) } }
        controller.onActivityStart()
        controller.start()
        // A launcher-looking start that was really the phone's automatic reopen: back to icon mode (never the HUD over a call).
        controller.onAutoOpen = { runOnUiThread { if (runtime.startGate.onAutoOpenMarker() && !minimised) { Log.i(TAG, "automatic reopen: icon mode"); minimise() } } }
        lifecycleScope.launch { runtime.pauses.ensureLoaded() }
        // Final review I2: a phone toast during our recording means the phone refused or ended the ask. Stop recording
        // (no Cancel: the phone already dropped it) so the wearer sees the toast instead of "tap to send".
        lifecycleScope.launch {
            combine(controller.frame, runtime.listening) { f, r -> f?.overlay to r }.collect { (o, r) ->
                if (askWatch.shouldEnd(r, o)) runtime.ptt.cancel()
            }
        }
        // While minimised the composition is stopped: overlays go to the pop-up, a Confirm opens the HUD (spec 2026-10-14 §4).
        lifecycleScope.launch { controller.frame.collect { onPhoneOverlay(it?.overlay) } }
        lifecycleScope.launch {
            controller.sessionOpen.collect { open -> if (open && minimised) sessionReopenedAtMs = SystemClock.uptimeMillis() }
        }
        if (granted(Manifest.permission.CAMERA)) ensureCaptureScheduled()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filterNot(::granted)
        if (missing.isNotEmpty()) requestPermissions(missing)
        if (savedInstanceState == null) handleIntent(intent) // a recreated activity must not capture again
        setContent { Hud() }
    }

    /** (Re)started: phone messages may open the CXR session again (spec §1.7 send gate). */
    override fun onStart() {
        super.onStart()
        controller.onActivityStart()
        runtime.hudPresence.onStarted()
        wasMinimised = minimised
        minimised = false
        runtime.minimisedIcon.hide() // the full HUD is back
        runtime.popup.hide()
        // In front now: start the keep-alive camera service so captures keep working after the wearer leaves the HUD.
        runtime.uiMode.set(UiModeStore.FULL)
        runtime.iconBlocked = false
        runtime.restarts.heartbeat() // alive and meant to be running (the stamp is not written while closed)
        if (granted(Manifest.permission.CAMERA)) CaptureService.startKeepAlive(this)
        // Fix wave I: back after a kill (or an explicit extra, consumed once): straight to icon mode, no HUD, unless the wearer opened it from the launcher.
        val extra = intent?.getBooleanExtra(EXTRA_START_MINIMISED, false) == true
        if (extra) intent?.removeExtra(EXTRA_START_MINIMISED)
        val source = StartSources.classify(intent?.action, intent?.categories, referrer?.host)
        if (runtime.startGate.onStart(source, extra)) {
            Log.i(TAG, "starting minimised (icon mode) source=$source")
            swipeTimer.post { minimise() }
        } else if (source == StartSource.LAUNCHER) Log.i(TAG, "opened from the launcher: showing the HUD")
    }

    /** Finishing closes the session at once; [onDestroy] may come late. */
    override fun onStop() {
        cancelGate() // leaving the HUD with the gate armed must never pause later (review focus 1)
        if (isFinishing) { runtime.uiMode.set(UiModeStore.HOME); runtime.restarts.onExit(); cancelAsk(); controller.onActivityExit(); CaptureService.stopKeepAlive(this) }
        runtime.hudPresence.onStopped(finishing = isFinishing)
        super.onStop()
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val cameBack = wasMinimised
        wasMinimised = false
        when {
            intent.getStringExtra(EXTRA_RETURN_TO) == RETURN_TO_MINIMISED -> {
                returnToMinimised = true
                autoOpenedAtMs = SystemClock.uptimeMillis()
                autoConfirmId = launchedConfirmId
                swipeTimer.removeCallbacks(confirmTimeout)
                swipeTimer.postDelayed(confirmTimeout, HudPresenter.CONFIRM_MS)
            }
            intent.action == ACTION_CAPTURE_NOW -> handleIntent(intent)
            // Spec §1.7 exception kept narrow: the phone's "Open on glasses" (a relaunch as the session reopens) never
            // pulls a minimised HUD over the front app; it goes straight back with a pop-up.
            cameBack && SystemClock.uptimeMillis() - sessionReopenedAtMs < OPEN_ON_GLASS_GRACE_MS -> {
                minimise()
                if (Settings.canDrawOverlays(this)) runtime.popup.show(OPENED_FROM_PHONE_POPUP, controller.settings.value.popupMs())
            }
            else -> { // the wearer opened the app themselves: the HUD stays in front after any Confirm
                returnToMinimised = false
                autoConfirmId = null
                swipeTimer.removeCallbacks(confirmTimeout)
            }
        }
    }

    /** A phone overlay while minimised: pop-up text, or open the HUD for a Confirm (with the overlay permission). */
    private fun onPhoneOverlay(o: Overlay?) {
        val auto = autoConfirmId
        if (returnToMinimised && auto != null && (o as? Overlay.Confirm)?.proposalId != auto) afterConfirm() // answered or withdrawn on the phone
        if (o == lastOverlay) return
        lastOverlay = o
        if (!minimised || !Settings.canDrawOverlays(this)) return
        if (o is Overlay.Confirm) {
            if (o.proposalId == launchedConfirmId) return
            launchedConfirmId = o.proposalId
            runCatching { applicationContext.startActivity(confirmIntent()) }.onFailure { Log.w(TAG, "confirm open refused: ${it.javaClass.simpleName}") }
            return
        }
        PopupOverlay.textFor(o, controller.settings.value.verbosity)?.let { runtime.popup.show(it, controller.settings.value.popupMs()) }
    }

    private fun confirmIntent() = Intent(this, MainActivity::class.java).putExtra(EXTRA_RETURN_TO, RETURN_TO_MINIMISED)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The vote went out, the window ended, or the phone cleared it: back where the wearer was. */
    private fun afterConfirm() {
        swipeTimer.removeCallbacks(confirmTimeout)
        autoConfirmId = null
        if (!returnToMinimised) return
        returnToMinimised = false
        minimise()
    }

    /** Review focus 2: right after an auto-open, a tap meant for the other app must not vote. */
    private fun voteBlocked(): Boolean = returnToMinimised && SystemClock.uptimeMillis() - autoOpenedAtMs < VOTE_GUARD_MS

    @Composable
    private fun Hud() {
        val frame by controller.frame.collectAsStateWithLifecycle()
        val settings by controller.settings.collectAsStateWithLifecycle()
        val connection by controller.connection.collectAsStateWithLifecycle()
        val pauses by runtime.pauses.records.collectAsStateWithLifecycle()
        val recording by runtime.listening.collectAsStateWithLifecycle()
        val photoCue by runtime.photoCue.active.collectAsStateWithLifecycle()
        val capture by runtime.captureStatus.collectAsStateWithLifecycle()
        val blink by runtime.captureBlink.active.collectAsStateWithLifecycle()
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
        val view = presenter.present(frame, settings.verbosity, nav, local, now, recording)
        // Votes follow the presented overlay: an expired, hidden or dismissed Confirm can never be answered unseen.
        val shownConfirm = view.overlay as? Overlay.Confirm
        LaunchedEffect(shownConfirm?.proposalId) { confirm.onConfirm(shownConfirm); highlightYes = confirm.highlightYes }
        Box(Modifier.fillMaxSize()) {
            HudRoot(
                HudModel(
                    view = view, frame = frame, settings = settings,
                    connection = connection, batteryPct = battery, paused = pauses.any { it.isActiveAt(now) },
                    nextCaptureAtMs = nextCaptureAt(), nowMs = now, nav = nav, confirmHighlightYes = highlightYes, recording = recording,
                    photoCue = photoCue,
                ),
            )
            // Heat stop and a capture that fell back to Fixed (spec P1.5, P1.6): small marks over the HUD, nothing when fine.
            CaptureStatusMarks(cooling = capture.cooling, degraded = capture.degraded, modifier = Modifier.align(Alignment.TopStart))
            CaptureCueMark(active = blink, modifier = Modifier.align(Alignment.TopEnd))
        }
    }

    /** Touchpad keys are ours: KEYCODE_NOTIFICATION must never reach the system, or it moves our task to the back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val r = when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.onKeyDown(event.keyCode, event.repeatCount, event.eventTime, System.currentTimeMillis(), exitGate !is ExitGate.Idle)
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
    override fun onBackPressed() = handle(keys.onBack(System.currentTimeMillis(), exitGate !is ExitGate.Idle))

    private fun scheduleSwipeClose() {
        swipeTimer.removeCallbacks(closeSwipe)
        keys.deadlineMs?.let { swipeTimer.postAtTime(closeSwipe, it) } // KeyEvent.eventTime and postAtTime share the uptime clock
    }

    private fun handle(inputs: List<PadInput>) {
        for (input in inputs) {
            val now = System.currentTimeMillis()
            val recording = runtime.listening.value
            val view = presenter.present(controller.frame.value, controller.settings.value.verbosity, nav, local, now, recording)
            val shown = view.overlay
            confirm.onConfirm(shown as? Overlay.Confirm)
            highlightYes = confirm.highlightYes
            val r = InputRouter.route(input, shown, controller.settings.value.gestures, recording, gate = view.page != null)
            if (r != Routed.ExitGate) cancelGate()
            when (r) {
                Routed.ExitGate -> onGateDoubleTap()
                Routed.ConfirmToggle -> if (!voteBlocked()) { confirm.onSwipe(); highlightYes = confirm.highlightYes }
                Routed.ConfirmChoose -> if (!voteBlocked()) confirm.onTap()?.let { controller.send(it); presenter.dismiss(shown); afterConfirm() }
                Routed.ConfirmNo -> if (!voteBlocked()) confirm.onNo()?.let { controller.send(it); presenter.dismiss(shown); afterConfirm() }
                Routed.FinishListening -> runtime.ptt.stop()
                Routed.CancelListening -> { runtime.ptt.cancel(); controller.send(Command.Cancel) }
                Routed.DismissOverlay -> dismiss(shown)
                // An Answer that carried the undo stays up (the phone drops the undo line); a toast goes.
                is Routed.Undo -> { controller.send(Command.Undo(r.proposalId)); if (shown is Overlay.Toast) presenter.dismiss(shown) }
                is Routed.Mapped -> onAction(r, shown, now)
            }
            nav = nav.touched(now)
        }
    }

    /** A page double-tap: arm the gate (countdown shown), or minimise when it was already armed. */
    private fun onGateDoubleTap() {
        val (g, a) = DoubleTapExit.onDoubleTap(exitGate, SystemClock.uptimeMillis())
        exitGate = g
        swipeTimer.removeCallbacks(gateTick)
        if (a == PageAction.EXIT) { clearCountdown(); exitApp(); return }
        showCountdown()
        swipeTimer.postDelayed(gateTick, GATE_TICK_MS)
    }

    private fun tickGate() {
        // Never armed under an overlay with its own double-tap: one arriving now ends the gate with no pause.
        val phone = controller.frame.value?.overlay
        // A Confirm/Answer in the 1.5 s "Minimising" window must not cancel the minimise the wearer asked for (fix wave I).
        val phoneBusy = phone is Overlay.Confirm || phone is Overlay.Listening || phone is Overlay.Answer
        if (DoubleTapExit.cancelsFor(exitGate, runtime.listening.value, phoneBusy)) { cancelGate(); return }
        val (g, a) = DoubleTapExit.onTick(exitGate, SystemClock.uptimeMillis())
        exitGate = g
        if (a == PageAction.MINIMISE) { clearCountdown(); minimise(); return }
        if (a == PageAction.TOGGLE_PAUSE) {
            clearCountdown()
            onAction(Routed.Mapped(GestureAction.PAUSE_RESUME), null, System.currentTimeMillis())
            return
        }
        if (g !is ExitGate.Idle) { showCountdown(); swipeTimer.postDelayed(gateTick, GATE_TICK_MS) }
    }

    private fun showCountdown() {
        DoubleTapExit.countdownText(exitGate, SystemClock.uptimeMillis())?.let { local = LocalOverlay.Toast(it, System.currentTimeMillis()) }
    }

    /** A tap, swipe or minimise while armed: no pause; the countdown goes. */
    private fun cancelGate() {
        if (exitGate == ExitGate.Idle) return
        exitGate = DoubleTapExit.onOtherInput(exitGate)
        swipeTimer.removeCallbacks(gateTick)
        clearCountdown()
    }

    private fun clearCountdown() {
        if (DoubleTapExit.isGateText((local as? LocalOverlay.Toast)?.text)) local = null
    }

    /**
     * The third page double-tap (fix wave H): leave completely. Tell the phone first (`pc_user_exit`, so it does not reopen the
     * app), then finish: onStop/onDestroy stop the keep-alive service, and with it the sampling session and the icon.
     */
    internal fun exitApp() {
        cancelGate()
        exitedByUser = true
        runtime.restarts.onUserExit(System.currentTimeMillis()) // repeated in every hello, in case this message is lost with the session
        // Finishing closes the CXR session at once and drops a message still in flight: wait for the hand-over (or ~300 ms) first.
        var tries = 0
        val sendAndFinish = object : Runnable {
            override fun run() {
                val handedOver = controller.sendUserExit()
                tries++
                if (handedOver || tries * EXIT_RETRY_MS >= EXIT_WAIT_MS) swipeTimer.postDelayed({ finish() }, EXIT_FLUSH_MS)
                else swipeTimer.postDelayed(this, EXIT_RETRY_MS)
            }
        }
        sendAndFinish.run()
    }

    /** Tests: the wearer exited with the third double-tap. */
    @VisibleForTesting internal var exitedByUser = false
        private set

    /** The exit window after the second page double-tap ended: our task goes to the back; capture keeps running in the keep-alive service. */
    internal fun minimise() {
        cancelGate()
        tasksMovedBack++
        runtime.uiMode.set(UiModeStore.ICON)
        moveTaskToBack(true)
        minimised = true
        runtime.hudPresence.onMinimised()
        if (!Settings.canDrawOverlays(this)) {
            // Capture runs but nothing shows it: count-only warning here, a Diagnostics row on the phone (via the hello).
            Log.w(TAG, "minimised without the overlay permission: no icon")
            runtime.iconBlocked = true; controller.sendHello()
            Toast.makeText(this, NO_OVERLAY_TOAST, Toast.LENGTH_SHORT).show(); return
        }
        runtime.iconBlocked = false
        // The icon means "capture is running": only while the keep-alive service holds the camera.
        if (CaptureService.liveInstance != null) runtime.minimisedIcon.show(controller.settings.value.iconCorner)
    }

    /** Our task is in the back after [minimise], until the activity starts again. */
    @VisibleForTesting internal var minimised = false
        private set

    /** Tests: how many times our task was sent to the back. */
    @VisibleForTesting internal var tasksMovedBack = 0
        private set

    /** A dismissed Answer also tells the phone, which stops speaking it (spec §5.1 step 5). */
    private fun dismiss(shown: Overlay?) {
        presenter.dismiss(shown)
        if (shown is Overlay.Answer) controller.send(Command.Cancel)
    }

    private fun onAction(r: Routed.Mapped, shown: Overlay?, now: Long) {
        val settings = controller.settings.value
        when (r.action) {
            GestureAction.NEXT_PAGE, GestureAction.PREV_PAGE -> {
                val next = nav.onSwipeAction(r.action, r.long, pagesFor(settings.verbosity), controller.frame.value?.today?.size ?: 0, now)
                if (next.page != nav.page) controller.send(Command.PageChanged(next.page))
                nav = next
            }
            GestureAction.ASK -> onAsk(now)
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
            GestureAction.DISMISS -> { dismiss(shown); local = null }
            GestureAction.NONE -> Unit
        }
    }

    /**
     * Tap = Ask (spec §5.1): starts push-to-talk; a tap while recording, or just after an auto-end, sends. Not while the
     * phone is offline, still connecting or outdated, or says voice can't work (spec §3.6, §5.1.1). Capture pauses don't
     * matter: Ask is a user action.
     */
    private fun onAsk(now: Long) {
        if (runtime.listening.value) { runtime.ptt.stop(); return }
        // BUG-R3-3: the VAD ended the listen a beat before this tap; the tap was the send, the ask is already on its way.
        if (runtime.ptt.takeLateTap()) { Log.i(TAG, "ptt: tap after auto-end taken as send"); return }
        HudCopy.askUnavailable(controller.connection.value, controller.settings.value.askBlockedReason)?.let { local = LocalOverlay.Toast(it, now); return }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            local = LocalOverlay.Toast(MIC_PERMISSION, now)
            requestPermissions(listOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        local = null
        askWatch.onStart(controller.frame.value?.overlay)
        runtime.ptt.start()
    }

    private fun pttText(p: PttProblem): String = when (p) {
        PttProblem.NO_PERMISSION -> MIC_PERMISSION
        PttProblem.MIC_UNAVAILABLE -> "Microphone unavailable"
        PttProblem.LINK_DOWN -> HudCopy.OFFLINE
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

    private fun requestPermissions(missing: List<String>) {
        if (permissionPending) return
        permissionPending = true
        permissions.launch(missing.toTypedArray())
    }

    /** Ruling (fix round 1): leaving mid-recording tells the phone to drop the ask, or it would answer what it heard. */
    private fun cancelAsk() {
        if (runtime.controller !== controller) return // a newer activity owns push-to-talk now
        if (runtime.ptt.cancel()) controller.send(Command.Cancel)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // CaptureService is the only capture path (spec §3.1); its scheduler drives the Glance countdown.
    // Smart mode samples on motion, with only a watchdog alarm: there is no next capture to count down to.
    private fun nextCaptureAt(): Long? = if (runtime.smartCapture.value) null else CaptureScheduler(this).nextTriggerAtMs()

    private fun ensureCaptureScheduled() { if (CaptureScheduler(this).ensureScheduled() == com.rokid.context.glasses.capture.Rearm.MISSED) local = LocalOverlay.Toast(CAPTURE_RESUMED_AFTER_STOP, System.currentTimeMillis()) }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != ACTION_CAPTURE_NOW) return
        CaptureService.captureNow(this, skipDedupe = i.getBooleanExtra(CaptureService.EXTRA_SKIP_DEDUPE, false))
    }

    override fun onDestroy() {
        swipeTimer.removeCallbacks(closeSwipe)
        swipeTimer.removeCallbacks(gateTick)
        swipeTimer.removeCallbacks(confirmTimeout)
        cancelAsk()
        controller.onActivityExit()
        if (isFinishing) { runtime.hudPresence.onExited(); CaptureService.stopKeepAlive(this); runtime.minimisedIcon.hide(); runtime.popup.hide() }
        runtime.detach(controller)
        super.onDestroy()
    }

    companion object {
        const val TAG = "PcGlasses"
        /** Start straight into icon mode (no HUD, no focus taken); also what a restart after a force-stop in icon mode does. */
        const val EXTRA_START_MINIMISED = "com.rokid.context.glasses.START_MINIMISED"
        private const val GATE_TICK_MS = 250L
        /** The exit message gets this long to leave before the activity finishes (retried every [EXIT_RETRY_MS] while CXR refuses it). */
        private const val EXIT_WAIT_MS = 300L
        private const val EXIT_RETRY_MS = 60L
        private const val EXIT_FLUSH_MS = 120L
        const val MIC_PERMISSION = "Allow the microphone on the glasses"
        /** Minimised without SYSTEM_ALERT_WINDOW: plain moveTaskToBack, no icon (spec 2026-10-14 §3). */
        const val NO_OVERLAY_TOAST = "Allow 'display over other apps' for the icon"
        const val EXTRA_RETURN_TO = "com.rokid.context.glasses.extra.RETURN_TO"
        const val RETURN_TO_MINIMISED = "MINIMISED"
        /** After an auto-open, votes wait this long (the Confirm must have been on screen). */
        const val VOTE_GUARD_MS = 600L
        /** A relaunch this soon after the session reopened while minimised is the phone's "Open on glasses". */
        const val OPEN_ON_GLASS_GRACE_MS = 5_000L
        const val OPENED_FROM_PHONE_POPUP = "Proactive Context is running"
        /** Phase 8 C3: shown once when reopening finds the capture alarm lost to a force-stop. */
        const val CAPTURE_RESUMED_AFTER_STOP = "Capture resumed \u2014 it stops while the app is closed"
        /** adb and foreground-fallback entry: capture one frame now. */
        const val ACTION_CAPTURE_NOW = "com.rokid.context.glasses.action.CAPTURE_NOW"
    }
}
