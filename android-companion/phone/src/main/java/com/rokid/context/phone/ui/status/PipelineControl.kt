package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.phone.hub.Hub
import com.rokid.context.ai.SafeLog

/** The ⏸/▶ switch behind Home, Activity, the paused banner and the notification (one source of truth). */
interface PipelineControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    /** A pause/resume tap: flips the newest *requested* state (Phase 4 carry), so a quick pause then resume never reads a stale [enabled]. */
    fun toggle()
    /** Phase 7: the active pause records behind [enabled] (phone clock); none for the legacy switch. */
    val activePauses: StateFlow<List<PauseRecord>> get() = NO_PAUSES
    /** Phase 7: when the last Resume from this control finished (phone clock), for "Still paused on glasses". */
    val lastResumeAtMs: StateFlow<Long?> get() = NO_RESUME
    /** Phase 7: a PHONE pause until [untilMs] (phone clock; null = until resumed). Legacy: a null pause only. */
    fun pauseUntil(untilMs: Long?) { if (untilMs == null) setEnabled(false) }
    /** Phase 7: resumes exactly [ids] (the ones a screen showed). Legacy: nothing. */
    fun resumeIds(ids: Set<String>) = Unit
    /** Phase 7: resumes the pauses stored now (loaded first; a notification tap after a process death). Legacy: Resume. */
    fun resumeLoaded() = setEnabled(true)
    /**
     * Whether already-captured frames are processed (ingest, drain, embedding, critic): the legacy pipeline flag. Capture
     * pauses are capture-only (Phase 7 fix round 1, M2). Legacy: the same as [enabled].
     */
    val processingEnabled: StateFlow<Boolean> get() = enabled

    companion object {
        /** Strict default for deps built without the hub: never paused, every call ignored. */
        val None: PipelineControl = object : PipelineControl {
            override val enabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
            override fun setEnabled(enabled: Boolean) = Unit
            override fun toggle() = Unit
            override fun pauseUntil(untilMs: Long?) = Unit
        }
    }
}

private val NO_PAUSES: StateFlow<List<PauseRecord>> = MutableStateFlow<List<PauseRecord>>(emptyList()).asStateFlow()
private val NO_RESUME: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

/** The part of the hub the pause control uses ([Hub] in production). */
interface PauseHub {
    val activePauses: StateFlow<List<PauseRecord>>
    suspend fun pausePhone(untilMs: Long?): PauseRecord
    suspend fun resume(ids: Set<String>)
    /** [pausePhone] plus whether the settings push reached the link (true only when it was accepted). Default: unknown = false. */
    suspend fun pausePhoneAcked(untilMs: Long?): Pair<PauseRecord, Boolean> = pausePhone(untilMs) to false
    /** [resume] plus whether the settings push reached the link (true only when it was accepted). */
    suspend fun resumeAcked(ids: Set<String>): Boolean { resume(ids); return false }
    /** The active pauses as stored (on IO), for a Resume made before [activePauses] has loaded. */
    suspend fun loadActive(): List<PauseRecord> = activePauses.value
}

fun Hub.asPauseHub(loadActive: suspend () -> List<PauseRecord>): PauseHub = object : PauseHub {
    override suspend fun loadActive(): List<PauseRecord> = loadActive()
    override val activePauses: StateFlow<List<PauseRecord>> get() = this@asPauseHub.activePauses
    override suspend fun pausePhone(untilMs: Long?): PauseRecord = this@asPauseHub.pausePhone(untilMs)
    override suspend fun resume(ids: Set<String>) = this@asPauseHub.resume(ids)
    override suspend fun pausePhoneAcked(untilMs: Long?) = this@asPauseHub.pausePhoneAcked(untilMs)
    override suspend fun resumeAcked(ids: Set<String>) = this@asPauseHub.resumeAcked(ids)
}

/** The legacy "pipeline enabled" flag, for the one-time Phase 7 migration (each call does prefs I/O: off Main only). */
interface LegacyFlag {
    fun migrated(): Boolean
    /** Marks the migration done; [heldPauseId] = the PHONE pause whose Resume releases the held backlog (null: none). */
    fun markMigrated(heldPauseId: String?)
    fun heldPauseId(): String?
    fun pipelineEnabled(): Boolean
    /** Turns the legacy flag on, resumes the workers it paused and clears [heldPauseId]. */
    fun enablePipeline()
}

/**
 * Phase 7 (R-65): ⏸ creates an indefinite PHONE pause record through the hub; ▶ resumes **only the ids active at the
 * moment of the tap** (spec §5.5), plus a pause queued by an earlier tap that had not landed yet. Taps run one at a
 * time, in tap order, on [io]. [enabled] = no active record. The legacy flag no longer follows ⏸: once, a legacy "off"
 * becomes one indefinite PHONE pause (marker in [legacyFlag]). The flag stays off, so frames and jobs held while it was
 * off stay held, until that pause is resumed by any path; then the flag is switched on and the workers resumed (fix
 * round 1, I1). [processingEnabled] = the flag.
 */
class PausePipelineControl(
    private val hub: PauseHub,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val legacyFlag: LegacyFlag,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PipelineControl {
    constructor(
        hub: Hub, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, legacyFlag: LegacyFlag,
        loadActive: suspend () -> List<PauseRecord>,
    ) : this(hub.asPauseHub(loadActive), scope, clock, legacyFlag)

    /** Plain prefs (the old control read the same flag at construction); updated on IO when the backlog is released. */
    private val processing = MutableStateFlow(legacyFlag.pipelineEnabled())
    override val processingEnabled: StateFlow<Boolean> = processing.asStateFlow()

    override val activePauses: StateFlow<List<PauseRecord>> = hub.activePauses
    override val enabled: StateFlow<Boolean> =
        hub.activePauses.map { it.isEmpty() }.stateIn(scope, SharingStarted.Eagerly, hub.activePauses.value.isEmpty())
    private val _lastResume = MutableStateFlow<Long?>(null)
    override val lastResumeAtMs: StateFlow<Long?> = _lastResume.asStateFlow()

    private sealed interface Op {
        data class Pause(val untilMs: Long?) : Op
        data class Resume(val shown: Set<String>, val includeQueued: Boolean = true, val loaded: Boolean = false) : Op
    }
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val lock = Any()
    /** The newest requested running state while taps are in flight (null = none in flight: read [activePauses]). */
    private var requested: Boolean? = null
    private var inFlight = 0
    /** Ids created by pause taps whose record a later Resume tap could not have seen yet. */
    private val createdByTaps = mutableSetOf<String>()

    init {
        scope.launch(io) {
            migrateLegacy()
            launch { watchHeld() }
            for (op in ops) {
                try {
                    run(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PcLog.w(TAG, "pause tap failed: ${SafeLog.describe(e)}")
                } finally {
                    synchronized(lock) { if (--inFlight == 0) requested = null }
                }
            }
        }
    }

    private suspend fun migrateLegacy() {
        try {
            if (legacyFlag.migrated()) return
            // No resumePipeline here: the backlog held while the flag was off waits for the user's Resume.
            val held = if (!legacyFlag.pipelineEnabled()) hub.pausePhone(null).pauseId else null
            legacyFlag.markMigrated(held)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "legacy pause migration failed: ${SafeLog.describe(e)}")
        }
    }

    /** Releases the held backlog once the migrated pause is no longer active (checked against the store). */
    private suspend fun watchHeld() {
        hub.activePauses.collect { shown ->
            try {
                val held = legacyFlag.heldPauseId() ?: return@collect
                if (shown.any { it.pauseId == held }) return@collect
                if (hub.loadActive().any { it.pauseId == held }) return@collect
                legacyFlag.enablePipeline()
                processing.value = legacyFlag.pipelineEnabled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PcLog.w(TAG, "backlog release failed: ${SafeLog.describe(e)}")
            }
        }
    }

    private suspend fun run(op: Op) {
        when (op) {
            is Op.Pause -> hub.pausePhone(op.untilMs).also { if (op.untilMs == null) createdByTaps += it.pauseId }
            is Op.Resume -> {
                val shown = if (op.loaded) hub.loadActive().map { it.pauseId }.toSet() else op.shown
                val ids = if (op.includeQueued) shown + createdByTaps else shown
                if (op.includeQueued) createdByTaps.clear() else createdByTaps.removeAll(shown)
                if (ids.isNotEmpty()) hub.resume(ids)
                _lastResume.value = clock()
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val op = if (enabled) Op.Resume(hub.activePauses.value.map { it.pauseId }.toSet()) else Op.Pause(null)
        synchronized(lock) { requested = enabled; inFlight++ }
        ops.trySend(op)
    }

    override fun pauseUntil(untilMs: Long?) {
        synchronized(lock) { requested = false; inFlight++ }
        ops.trySend(Op.Pause(untilMs))
    }

    /** Exactly [ids]: a screen's "Resume all shown" (a pause queued by a tap is not added). */
    override fun resumeIds(ids: Set<String>) {
        synchronized(lock) { inFlight++ }
        ops.trySend(Op.Resume(ids, includeQueued = false))
    }

    override fun resumeLoaded() {
        synchronized(lock) { requested = true; inFlight++ }
        ops.trySend(Op.Resume(emptySet(), loaded = true))
    }

    override fun toggle() = synchronized(lock) { setEnabled(!(requested ?: hub.activePauses.value.isEmpty())) }

    private companion object {
        const val TAG = "PcPause"
    }
}

/**
 * Phase 4: the legacy "pipeline enabled" flag + worker pause/resume. Phase 7 replaces it with PauseRecords (R-65).
 * Final-review I3: a tap only queues the change. The flag commits, the queue TTL freeze/extend (a rewrite of
 * `queue_runtime.log`) and the scheduler calls run on [io], one tap at a time in tap order; [enabled] is published
 * once the change is persisted.
 */
class LegacyPipelineControl(
    private val context: Context,
    private val scheduler: OrchestrationScheduler,
    private val io: CoroutineDispatcher = SERIAL_IO,
    private val scope: CoroutineScope = APP_SCOPE,
) : PipelineControl {
    // Plain prefs only: AppConfigStore.get also opens the Keystore-backed secrets (Threads constraint).
    private val state = MutableStateFlow(AppConfigStore.isPipelineEnabled(context))
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    private val lock = Any()
    /** What the newest tap asked for; resynced to [enabled] once nothing is in flight. */
    private var requested = state.value
    private var inFlight = 0

    override fun setEnabled(enabled: Boolean) {
        synchronized(lock) { requested = enabled; inFlight++ }
        scope.launch(io) {
            try {
                // No cached-state early return: scheduling is idempotent, so always apply and then publish.
                AppConfigStore.setPipelineEnabled(context, enabled) // stamp / TTL freeze or extend / flag, in that order
                if (enabled) scheduler.resumePipeline() else scheduler.pausePipeline()
                state.value = AppConfigStore.isPipelineEnabled(context) // publish what was actually persisted
            } finally {
                synchronized(lock) { if (--inFlight == 0) requested = state.value }
            }
        }
    }

    override fun toggle() = synchronized(lock) { setEnabled(!requested) }

    private companion object {
        /** One at a time, FIFO: a quick pause-then-resume applies in tap order. */
        val SERIAL_IO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
        /** Process lifetime: leaving the screen never drops a tap half-applied. */
        val APP_SCOPE = CoroutineScope(SupervisorJob())
    }
}
