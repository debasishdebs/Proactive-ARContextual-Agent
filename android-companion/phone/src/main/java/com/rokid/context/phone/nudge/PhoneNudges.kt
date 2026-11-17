package com.rokid.context.phone.nudge

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.orchestration.nudge.NudgeEnvironment
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.phone.hub.HubNudges
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The phone side of nudges (R-61). */
object PhoneNudges {
    /**
     * Gives the nudge engine the HUD settings (quiet hours, verbosity, zone) and the hub the newest nudge. Built once
     * with the hub; the DB is reached lazily on IO.
     */
    fun hubPort(app: Context, hud: () -> HudSettings): HubNudges {
        NudgeEnvironment.hud = { hud().let { NudgeHud(it.quietHours, it.verbosity, ZoneId.systemDefault()) } }
        val guard = CoroutineExceptionHandler { _, t -> PcLog.w("PhoneNudges", "nudge port failed: ${t.javaClass.simpleName}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)
        val rows: Flow<List<NudgeEntity>> = flow { emitAll(MemoryGraph.get(app).nudges.recent(if (ForgetV2.on) NudgePort.WINDOW else 1)) }
        return NudgePort(rows, scope, System::currentTimeMillis)
    }
}

/**
 * [latest]: the newest nudge as a Glance one-liner (≤ 80 chars) when it was not phone-only and is ≤ 6 h old.
 * [overlay]: the HUD "HEADS UP" toast for 8 s, only for a `toast` (Max) nudge stored after this port started, so a
 * restart never replays one. A redacted row (Forget) clears both at once, and so does the deletion of the row that owns the toast (N1).
 */
class NudgePort(
    rows: Flow<List<NudgeEntity>>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val toastMs: Long = TOAST_MS,
    /** `forgetV2` (read on every emission): OFF keeps the v1 behaviour exactly, the owner-presence rule below is not evaluated. */
    private val forgetV2: () -> Boolean = { ForgetV2.on },
) : HubNudges {
    private val startMs = clock()
    private val line = MutableStateFlow<NudgeLine?>(null)
    private val toast = MutableStateFlow<Overlay.Nudge?>(null)
    override val latest: StateFlow<NudgeLine?> = line.asStateFlow()
    override val overlay: StateFlow<Overlay.Nudge?> = toast.asStateFlow()

    private var toasted: String? = null
    private var toastJob: Job? = null
    private var toastOwner: String? = null
    private var expiryJob: Job? = null

    init {
        scope.launch { rows.collect { on(it) } }
    }

    private fun on(all: List<NudgeEntity>) {
        val n = all.firstOrNull()
        expiryJob?.cancel()
        if (n == null || n.redacted) {
            line.value = null
            toastJob?.cancel(); toast.value = null
            return
        }
        // Forget N1 (forgetV2): the toast is owned by ONE row; when that row is no longer among the emitted rows (deleted, or redacted) its text must not stay on the glasses/phone, whatever the newest row is (older, newer or equal timestamp).
        if (forgetV2() && toast.value != null && all.none { it.id == toastOwner && !it.redacted }) { toastJob?.cancel(); toast.value = null; toastOwner = null }
        val text = n.text.take(MAX_TEXT)
        val age = clock() - n.ts
        line.value = if (n.shownOn != "phone" && age <= MAX_AGE_MS) NudgeLine(text, n.ts) else null
        if (line.value != null) expiryJob = scope.launch { delay(MAX_AGE_MS - age + 1); line.value = null }
        if (n.shownOn == "toast" && n.ts >= startMs && toasted != n.id) {
            toasted = n.id
            val o = Overlay.Nudge(text)
            toast.value = o
            toastOwner = n.id
            toastJob?.cancel()
            toastJob = scope.launch { delay(toastMs); if (toast.value === o) toast.value = null }
        }
    }

    companion object {
        const val TOAST_MS = 8_000L
        const val MAX_AGE_MS = 6 * 3_600_000L
        const val MAX_TEXT = 80
        /** The newest rows observed: enough to tell whether the toast owner is still stored. */
        const val WINDOW = 16
    }
}
