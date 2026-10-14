package com.rokid.context.phone.actions

import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ActionCopy
import com.rokid.context.connectors.actions.ActionEngine
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.HudSurface
import com.rokid.context.connectors.actions.Proposed
import com.rokid.context.core.model.Overlay
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.phone.hub.HubActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The hub's actions port (R-62): a HUD Confirm while the newest CONFIRM proposal is PENDING (cleared on any terminal
 * state or expiry), or the undo toast for 10 s after an auto-executed one (restarted once when it first reaches the
 * glasses, BUG-R2-5), or for [OFF_NOTICE_MS] the "<type> is off" notice when an ask proposed a turned-off type. HUD votes
 * go through the engine's CAS, so a
 * late or second vote is a no-op.
 */
class HubActionsImpl(
    private val engine: ActionEngine,
    private val repo: ProposalRepository,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : HubActions {
    private val _overlay = MutableStateFlow<Overlay?>(null)
    override val overlay: StateFlow<Overlay?> = _overlay.asStateFlow()
    private var toastTimer: Job? = null
    /** The undo toast whose window has not yet been restarted by its first frame on the glasses (BUG-R2-5). */
    @Volatile private var unshownUndo: String? = null

    init {
        scope.launch { engine.events.collect { guarded { onProposed(it) } } }
        scope.launch { repo.pending().collect { rows -> guarded { clearConfirmUnless(rows.map { it.id }.toSet()) } } }
    }

    override fun onConfirm(proposalId: String, yes: Boolean) {
        scope.launch { guarded { engine.respond(proposalId, yes, HUD) }; clear(proposalId) }
    }

    override fun onUndo(proposalId: String) {
        scope.launch { guarded { engine.undo(proposalId, HUD) }; clear(proposalId) }
    }

    /**
     * BUG-R2-5: the first frame that carries the undo (as the toast, or on the Answer) restarts its 10 s, so time spent
     * under the ask's own Thinking does not eat the window; the toast is then kept for the new deadline.
     */
    override fun onShown(overlay: Overlay?) {
        val id = when (overlay) {
            is Overlay.Toast -> overlay.undoProposalId
            is Overlay.Answer -> overlay.undoProposalId
            else -> null
        } ?: return
        if (id != unshownUndo) return
        unshownUndo = null
        scope.launch {
            guarded {
                val until = engine.restartUndoWindow(id) ?: return@guarded
                if ((_overlay.value as? Overlay.Toast)?.undoProposalId == id) scheduleClear(id, until)
            }
        }
    }

    private fun scheduleClear(proposalId: String, until: Long) {
        toastTimer?.cancel()
        toastTimer = scope.launch { delay((until - engine.now()).coerceAtLeast(0)); clear(proposalId) }
    }

    private suspend fun onProposed(p: Proposed) {
        when (p.hud) {
            HudSurface.CONFIRM -> if (p.state == ProposalState.PENDING) confirmFor(p.proposalId)?.let { toastTimer?.cancel(); _overlay.value = it }
            HudSurface.UNDO_TOAST -> if (p.state == ProposalState.EXECUTED) {
                toastTimer?.cancel()
                unshownUndo = p.proposalId
                _overlay.value = Overlay.Toast(UNDO_TOAST, undoProposalId = p.proposalId)
                val until = p.undoUntilMs ?: return
                scheduleClear(p.proposalId, until)
            }
            HudSurface.OFF_NOTICE -> if (p.state == ProposalState.SUPPRESSED) {
                val type = repo.byId(p.proposalId)?.let { ActionParams.typeOf(it.type) } ?: return
                val notice = Overlay.Toast(ActionCopy.offNotice(type))
                toastTimer?.cancel()
                _overlay.value = notice
                toastTimer = scope.launch { delay(OFF_NOTICE_MS); _overlay.compareAndSet(notice, null) }
            }
            HudSurface.NONE -> Unit
        }
    }

    private suspend fun confirmFor(id: String): Overlay.Confirm? {
        val row = repo.byId(id) ?: return null
        val type = ActionParams.typeOf(row.type) ?: return null
        val params = ActionParams.decode(type, row.paramsJson) ?: return null
        return Overlay.Confirm(id, ActionCopy.clip(ActionCopy.confirmTitle(type)), ActionCopy.clip(ActionCopy.summary(params, zone())))
    }

    /** A shown Confirm whose proposal left PENDING (answered anywhere, or expired) is cleared. */
    private suspend fun clearConfirmUnless(pendingIds: Set<String>) {
        val c = _overlay.value as? Overlay.Confirm ?: return
        if (c.proposalId in pendingIds) return
        if (repo.byId(c.proposalId)?.state != ProposalState.PENDING.name) clear(c.proposalId)
    }

    private fun clear(proposalId: String) {
        _overlay.compareAndSet(_overlay.value?.takeIf { it.proposalIdOrNull() == proposalId } ?: return, null)
    }

    private fun Overlay.proposalIdOrNull(): String? = when (this) {
        is Overlay.Confirm -> proposalId
        is Overlay.Toast -> undoProposalId
        else -> null
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (c: CancellationException) { throw c } catch (t: Throwable) {
            try { Log.w(TAG, "actions port: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
        }
    }

    companion object {
        const val HUD = "hud"
        const val UNDO_TOAST = "Added \u00b7 double-tap undo"
        const val OFF_NOTICE_MS = 6_000L
        private const val TAG = "HubActions"
    }
}
