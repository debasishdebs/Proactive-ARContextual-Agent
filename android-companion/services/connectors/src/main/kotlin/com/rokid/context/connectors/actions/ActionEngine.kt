package com.rokid.context.connectors.actions

import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.executors.ActionExecutor
import com.rokid.context.connectors.actions.executors.ExecResult
import com.rokid.context.connectors.actions.executors.FailCode
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.Decision
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.Resolve
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

enum class HudSurface { CONFIRM, UNDO_TOAST, NONE }

/** What [ActionEngine.propose] stored, and which HUD surface it asks for. [undoUntilMs] is set only for an auto-executed action. */
data class Proposed(val proposalId: String, val state: ProposalState, val hud: HudSurface, val undoUntilMs: Long?)

/**
 * The action behaviour matrix (spec §5.3, R-62). The phone owns expiry; [ProposalRepository.resolve] is the only way out
 * of PENDING, so exactly one surface (HUD, phone, notification, expiry) wins. Executors are idempotent per proposalId and
 * [recover] re-runs a row left EXECUTING by process death. A timeout never becomes ✓.
 */
class ActionEngine(
    private val repo: ProposalRepository,
    private val settings: ActionSettingsStore,
    private val executors: Map<ActionType, ActionExecutor>,
    private val verbosity: () -> Verbosity,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Extra action_log rows (e.g. "PAST"); production wires ActionDao.log. */
    private val audit: suspend (proposalId: String, transition: String, detail: String, nowMs: Long) -> Unit = { _, _, _, _ -> },
) : ProposalIntake {
    private val _events = MutableSharedFlow<Proposed>(extraBufferCapacity = 32)
    val events: SharedFlow<Proposed> = _events.asSharedFlow()

    private val undoUntil = ConcurrentHashMap<String, Long>()

    /**
     * One proposal per (origin, originId): the id is [idFor], so a repeat call (a re-run ask with the same askId) loses
     * the insert, returns the first proposal (HUD NONE) and executes nothing.
     */
    suspend fun propose(origin: ProposalOrigin, originId: String, a: ProposedAction, nowMs: Long): Proposed? = withContext(work) {
        val (proposed, run) = store(origin, originId, ActionParams.from(a, zone()), nowMs)
            ?: return@withContext repo.byId(idFor(origin, originId))?.let(::existing)
        val out = if (run != null) {
            val ok = execute(run)
            if (ok) proposed.copy(state = ProposalState.EXECUTED).also { undoUntil[it.proposalId] = it.undoUntilMs!! }
            else proposed.copy(state = ProposalState.FAILED, hud = HudSurface.NONE, undoUntilMs = null)
        } else proposed
        if (out.state == ProposalState.PENDING) scheduleExpiry(out.proposalId, nowMs + ttl(origin) - clock())
        _events.tryEmit(out)
        out
    }

    /** [by] ∈ hud | phone | notification. A late or second answer gets `Lost(current)`. */
    suspend fun respond(proposalId: String, yes: Boolean, by: String): Resolve = withContext(work) {
        val r = repo.resolve(proposalId, if (yes) Decision.APPROVE else Decision.DECLINE, by, clock())
        if (r == Resolve.Won(ProposalState.EXECUTING)) {
            repo.byId(proposalId)?.let { if (execute(it)) undoUntil[proposalId] = clock() + UNDO_MS }
        }
        r
    }

    /** Only while now < undoUntil (10 s after execution). The effect is reversed first, then EXECUTED → DECLINED. */
    suspend fun undo(proposalId: String, by: String): Boolean = withContext(work) {
        val until = undoUntil[proposalId] ?: return@withContext false
        if (clock() >= until) return@withContext false
        val p = repo.byId(proposalId)?.takeIf { it.state == ProposalState.EXECUTED.name } ?: return@withContext false
        val exec = ActionParams.typeOf(p.type)?.let(executors::get) ?: return@withContext false
        val reversed = try { exec.undo(p) } catch (c: CancellationException) { throw c } catch (t: Throwable) { log("undo", t); false }
        reversed && repo.markUndone(proposalId, by, clock()).also { if (it) undoUntil.remove(proposalId) }
    }

    fun undoUntil(proposalId: String): Long? = undoUntil[proposalId]

    /** At install: re-execute rows left EXECUTING (idempotent executors), expire what is due, re-arm the other timers. */
    suspend fun recover() = withContext(work) {
        repo.inState(ProposalState.EXECUTING).forEach { execute(it) }
        val now = clock()
        repo.expireDue(now)
        repo.inState(ProposalState.PENDING).forEach { scheduleExpiry(it.id, it.expiresAt - now) }
    }

    override suspend fun onProposed(origin: ProposalOrigin, originId: String, action: ProposedAction, nowMs: Long): String? = try {
        propose(origin, originId, action, nowMs)?.proposalId
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        log("intake", t)
        null
    }

    /** The proposal plus the row to execute now when it was inserted EXECUTING (no confirm); null = the id already existed. */
    private suspend fun store(origin: ProposalOrigin, originId: String, params: ActionParams, nowMs: Long): Pair<Proposed, ProposalEntity?>? {
        val id = idFor(origin, originId)
        val toggle = settings.toggle(params.type)
        val hudWouldConfirm = origin == ProposalOrigin.ASK || verbosity() == Verbosity.MAX
        val past = params.firesAtMs?.let { it <= nowMs } == true
        val (state, expiresAt) = when {
            past -> ProposalState.EXPIRED to nowMs
            !toggle.enabled -> ProposalState.SUPPRESSED to nowMs
            toggle.alwaysConfirm -> ProposalState.PENDING to nowMs + ttl(origin)
            else -> ProposalState.EXECUTING to nowMs
        }
        val row = ProposalEntity(
            id, origin.name, originId, params.type.name, ActionParams.encode(params), state.name, expiresAt,
            resolvedBy = if (state == ProposalState.EXECUTING) AUTO else null, createdRef = null, createdAt = nowMs,
        )
        if (!repo.create(row, nowMs)) return null
        if (past) audit(id, PAST, "", nowMs)
        return when (state) {
            ProposalState.PENDING -> Proposed(id, state, if (hudWouldConfirm) HudSurface.CONFIRM else HudSurface.NONE, null) to null
            ProposalState.EXECUTING -> Proposed(id, state, if (hudWouldConfirm) HudSurface.UNDO_TOAST else HudSurface.NONE, nowMs + UNDO_MS) to row
            else -> Proposed(id, state, HudSurface.NONE, null) to null
        }
    }

    private fun existing(p: ProposalEntity) =
        Proposed(p.id, ProposalState.entries.firstOrNull { it.name == p.state } ?: ProposalState.FAILED, HudSurface.NONE, null)

    /** EXECUTING → EXECUTED / FAILED. True when it ended EXECUTED. Never throws (except cancellation). */
    private suspend fun execute(p: ProposalEntity): Boolean {
        val exec = ActionParams.typeOf(p.type)?.let(executors::get)
        val result = try {
            exec?.execute(p) ?: ExecResult.Failed(FailCode.UNKNOWN)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("execute", t)
            ExecResult.Failed(FailCode.UNKNOWN)
        }
        return when (result) {
            is ExecResult.Done -> repo.markExecuted(p.id, result.createdRef, clock())
            is ExecResult.Failed -> { repo.markFailed(p.id, result.code.name, clock()); false }
        }
    }

    private fun scheduleExpiry(id: String, inMs: Long) {
        scope.launch {
            delay(inMs.coerceAtLeast(0))
            try {
                withContext(work) { repo.expireDue(clock()) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("expiry", t)
            }
        }
    }

    private fun ttl(origin: ProposalOrigin) = if (origin == ProposalOrigin.ASK) ASK_TTL_MS else NUDGE_TTL_MS

    private fun log(what: String, t: Throwable) {
        try { Log.w(TAG, "$what failed: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { /* plain JVM */ }
    }

    companion object {
        /** Deterministic (controller ruling): one proposal per origin row. */
        fun idFor(origin: ProposalOrigin, originId: String) = "prop_${origin.name}_$originId"

        const val ASK_TTL_MS = 60_000L
        const val NUDGE_TTL_MS = 600_000L
        const val UNDO_MS = 10_000L
        const val AUTO = "auto"
        const val PAST = "PAST"
        private const val TAG = "ActionEngine"
    }
}
