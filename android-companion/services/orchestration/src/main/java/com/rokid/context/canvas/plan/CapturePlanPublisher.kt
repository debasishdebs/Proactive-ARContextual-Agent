package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.LiveMode
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPublishEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The result of a plan publish or a plan action: the fixed [reasons] when blocked. */
sealed interface PlanOutcome {
    data class Done(val publishId: Long, val groupId: String?, val mode: PlanMode, val configHash: String?, val gen: Long?) : PlanOutcome
    data class Blocked(val reasons: List<String>) : PlanOutcome
}

/** The Settings row and the Diagnostics plan card (S12, S26): plain data, rendered by the phone UI. [rows] are the per-component states of the active group. */
data class PlanStatus(val active: ActivePlan?, val rows: List<String>)
data class ActivePlan(val publishId: Long, val groupId: String, val name: String, val version: Int, val mode: String, val configHash: String, val state: String, val sentAtMs: Long? = null) {
    /** "Capture plan: <name> v<N> (<mode>) · Use settings" */
    val settingsRow: String get() = PlanTexts.settingsRow(name, version, mode)
    /** What a settings row the plan resolved shows. */
    val frozenLabel: String get() = PlanTexts.frozen(version)
}

object PlanTexts {
    fun settingsRow(name: String, version: Int, mode: String) = "Capture plan: $name v$version (${mode.lowercase()}) · Use settings"
    fun frozen(version: Int) = "Frozen in canvas plan v$version"
}

/**
 * Publishes a frozen version's capture half as a `capture_plan` group (S25, S26, S11) and runs the three rollback actions. Everything the glasses are told goes through
 * [PlanPublishGroups]; the ASYNC half publishes as an interpreter ([PlanActivation]) only after the glasses report EFFECTIVE (S25), so a rejected or unanswered plan never
 * changes the phone.
 *
 * One mutex serialises publish and the actions (like [CanvasPublisher]). Rulings: undo reinstates the previous active plan in its recorded mode, a TRIAL becoming SHADOW when no trial is
 * left or M1 is not recorded; restore previous re-publishes the newest earlier non-reverted plan in its own mode with the 60-minute TRIAL bound again (a counted trial, refused like a
 * publish when none is left); every reinstatement is a NEW publish row (a clone), so the stack, the trial count and the history stay honest.
 */
class CapturePlanPublisher(
    private val canvas: CanvasDao,
    private val groups: PlanPublishGroups,
    private val store: GroupStore,
    private val gates: PlanEvidenceGates,
    private val publisher: CanvasPublisher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settingsRev: suspend () -> Long = { 0L },
    private val newGroupId: () -> String = { "g_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    private val lock = Mutex()

    /** The gates for publishing [pipelineId] v[version] in [mode], without publishing (the canvas "check" button). */
    suspend fun check(pipelineId: String, version: Int, mode: PlanMode): GateReport? {
        val c = prepare(pipelineId, version) ?: return null
        return gates.evaluate(c.pipeline, c.compile, mode)
    }

    private class Prepared(val pipeline: com.rokid.context.ai.canvas.Pipeline, val compile: com.rokid.context.ai.canvas.plan.CompileResult, val hash: String)

    private suspend fun prepare(pipelineId: String, version: Int): Prepared? {
        val v = canvas.version(pipelineId, version) ?: return null
        val p = PipelineJson.decode(v.json).getOrNull() ?: return null
        return Prepared(p, PlanCompiler.compile(p, settingsRev()), v.hash)
    }

    suspend fun publish(pipelineId: String, version: Int, mode: PlanMode): PlanOutcome = lock.withLock {
        if (mode == PlanMode.SETTINGS) return@withLock PlanOutcome.Blocked(listOf("Use settings is an action, not a publish."))
        val c = prepare(pipelineId, version) ?: return@withLock PlanOutcome.Blocked(listOf("Version not found"))
        if (!PlanShape.hasCaptureHalf(c.pipeline)) return@withLock PlanOutcome.Blocked(listOf(NO_CAPTURE_HALF))
        val report = gates.evaluate(c.pipeline, c.compile, mode)
        if (!report.ok) return@withLock PlanOutcome.Blocked(report.reasons)
        val plan = PlanEvidenceGates.fillMeta(c.compile.plan!!, report.evidence?.opensP95PerMin.orEmpty())
        val planJson = PlanJson.encode(plan)
        if (PlanValidator.check(planJson).first.isNotEmpty()) return@withLock PlanOutcome.Blocked(listOf(PUBLISH_FAILED))
        compensateUnknown()
        send(pipelineId, version, c.hash, mode, plan.configHash, planJson, newGroupId(), previous = currentDesired(null))
    }

    /** Inserts the publish row, sends the desired state, and records the group's gen and state on the row. */
    private suspend fun send(pipelineId: String, version: Int, versionHash: String, mode: PlanMode, configHash: String, planJson: String, groupId: String, previous: PreviousDesired): PlanOutcome {
        val now = clock()
        val trialMs = if (mode == PlanMode.TRIAL) PlanEvidenceGates.TRIAL_MAX_MS else null
        val snapshot = buildJsonObject { put("pipelineId", pipelineId); put("version", version); put("configHash", configHash) }.toString()
        val rowId = try {
            canvas.insertPublish(CanvasPublishEntity(
                pipelineId = pipelineId, version = version, hash = versionHash, publishedAt = now, rolledBackAt = null, snapshotJson = snapshot, kind = CanvasPublisher.KIND_CAPTURE_PLAN,
                mode = mode.name, groupId = groupId, component = PlanPublishGroups.COMPONENT, planJson = planJson, configHash = configHash, gen = null, state = "DESIRED",
                trialEndsAt = trialMs?.let { now + it },
            ))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return PlanOutcome.Blocked(listOf(PUBLISH_FAILED)) }
        try {
            groups.publish(groupId, nextPublishId(groupId, rowId), mode, planJson, configHash, trialMs, previous)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            canvas.markRolledBackWithReason(rowId, clock(), "publish_failed")
            return PlanOutcome.Blocked(listOf(PUBLISH_FAILED))
        }
        val g = store.get(groupId)
        canvas.setPlanPublishState(rowId, g?.gen, g?.state, trialMs?.let { now + it })
        return PlanOutcome.Done(rowId, groupId, mode, configHash, g?.gen)
    }

    private suspend fun nextPublishId(groupId: String, floor: Long): Long = maxOf(floor, (store.get(groupId)?.publishId ?: 0L) + 1)

    /** A group left UNKNOWN_REMOTE is compensated before anything newer is sent: a new gen carrying what was desired before it (S25). */
    private suspend fun compensateUnknown() {
        for (g in store.all().filter { it.state == "UNKNOWN_REMOTE" }) {
            groups.compensate(g.groupId, currentDesired(excludingGroup = g.groupId))
        }
    }

    /** The plan the glasses run as far as the phone knows (the newest active capture plan outside [excludingGroup]), or "use settings". */
    private suspend fun currentDesired(excludingGroup: String?): PreviousDesired {
        val row = canvas.activePublishes().firstOrNull { it.component == PlanPublishGroups.COMPONENT && it.groupId != excludingGroup && it.planJson != null && stateOk(it) }
            ?: return PreviousDesired(PlanMode.SETTINGS, null, null)
        return PreviousDesired(PlanMode.valueOf(row.mode!!), row.planJson, row.configHash)
    }

    private suspend fun stateOk(r: CanvasPublishEntity): Boolean {
        val g = r.groupId ?: return false
        return store.get(g)?.state.let { it != "FAILED" && it != "REVERTED" }
    }

    // ---- S26: the three actions ----

    /**
     * Undo latest publish (slice 2 S10's stack, group-aware): the newest active publish of ANY kind is undone as one unit, ASYNC first, then the phone RT and the glasses (one
     * new desired state). [expectedId] is the publish the caller saw as the newest (an auto-rollback that won the race refuses the click). A publish with no group is slice 2's.
     */
    suspend fun undoLatest(expectedId: Long): CanvasPublisher.Outcome = lock.withLock {
        val active = canvas.activePublishes()
        if (active.none { it.id == expectedId }) return@withLock CanvasPublisher.Outcome.Blocked(listOf("That publish was already rolled back."))
        if (active.first().id != expectedId) return@withLock CanvasPublisher.Outcome.Blocked(listOf("A newer publish exists. Reload and try again."))
        val gid = active.first().groupId ?: return@withLock publisher.rollback(expectedId)
        val rows = active.filter { it.groupId == gid }                       // newest first
        // Everything that can refuse the reinstatement is checked BEFORE anything is rolled back: a refused undo changes nothing (S26).
        val plan = rows.firstOrNull { it.component == PlanPublishGroups.COMPONENT }
        val previous = plan?.let { canvas.activePublishes().firstOrNull { p -> p.component == PlanPublishGroups.COMPONENT && p.groupId != gid && p.planJson != null && stateOk(p) } }
        val mode = previous?.let { reinstateMode(it, fallbackToShadow = true) }
        if (mode is Refusal) return@withLock CanvasPublisher.Outcome.Blocked(mode.out.reasons)
        for (r in rows.filter { it.kind == CanvasPublisher.KIND_INTERPRETER }) {
            val o = publisher.rollback(r.id)                                    // 1. ASYNC (the interpreter rows are the group's newest)
            if (o is CanvasPublisher.Outcome.Blocked) return@withLock o
        }
        if (plan != null) {
            canvas.markRolledBackWithReason(plan.id, clock(), "undo")
            val out = if (previous != null) reinstate(previous, mode as PlanMode) else sendSettings()   // 2 and 3: phone RT and glasses, as one desired state
            if (out is PlanOutcome.Blocked) return@withLock CanvasPublisher.Outcome.Blocked(out.reasons)
        }
        CanvasPublisher.Outcome.Published(expectedId, emptyList())
    }

    /** Restore previous capture plan (capture_plan only): the newest earlier non-reverted plan is desired again, in its own recorded mode, TRIAL re-bounded and counted. */
    suspend fun restorePrevious(): PlanOutcome = lock.withLock {
        val rows = canvas.capturePlanPublishes()                              // newest first, rolled back or not
        val current = rows.firstOrNull { it.rolledBackAt == null }
        val earlier = if (current == null) rows else rows.dropWhile { it.id >= current.id }
        val prev = earlier.firstOrNull { it.planJson != null && it.mode in RESTORABLE && stateOk(it) }
            ?: return@withLock PlanOutcome.Blocked(listOf("There is no earlier capture plan to restore."))
        val mode = reinstateMode(prev, fallbackToShadow = false)
        if (mode is Refusal) return@withLock mode.out
        val out = reinstate(prev, mode as PlanMode)
        if (out is PlanOutcome.Done && current != null) canvas.markRolledBackWithReason(current.id, clock(), "restore_previous")
        out
    }

    /** Use settings: the capture overrides are disabled (desired = SETTINGS on the glasses and the phone RT). ASYNC is untouched. */
    suspend fun useSettings(): PlanOutcome = lock.withLock {
        canvas.activePublishes().filter { it.component == PlanPublishGroups.COMPONENT }.forEach { canvas.markRolledBackWithReason(it.id, clock(), "use_settings") }
        sendSettings()
    }

    private suspend fun sendSettings(): PlanOutcome {
        compensateUnknown()
        val gid = newGroupId()
        return try {
            groups.publish(gid, clock(), PlanMode.SETTINGS, null, null, null)
            PlanOutcome.Done(0, gid, PlanMode.SETTINGS, null, store.get(gid)?.gen)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { PlanOutcome.Blocked(listOf(PUBLISH_FAILED)) }
    }

    private class Refusal(val out: PlanOutcome.Blocked)

    /** Everything that can refuse a reinstatement of [row], checked without changing anything: the mode it would be sent in (a TRIAL no longer allowed becomes SHADOW when [fallbackToShadow]) or a [Refusal]. */
    private suspend fun reinstateMode(row: CanvasPublishEntity, fallbackToShadow: Boolean): Any {
        var mode = PlanMode.valueOf(row.mode!!)
        val hash = row.configHash ?: return Refusal(PlanOutcome.Blocked(listOf(PUBLISH_FAILED)))
        if (mode == PlanMode.TRIAL) {
            val refusal = gates.trialAllowed(hash)
            if (refusal != null) { if (fallbackToShadow) mode = PlanMode.SHADOW else return Refusal(PlanOutcome.Blocked(listOf(refusal))) }
        }
        if (!gates.glassesReady()) return Refusal(PlanOutcome.Blocked(listOf(PlanEvidenceGates.G5)))
        return mode
    }

    /** A new publish of an old plan: a clone row in a new group (the original is marked superseded). A TRIAL is a new, counted trial. The caller has run [reinstateMode]. */
    private suspend fun reinstate(row: CanvasPublishEntity, mode: PlanMode): PlanOutcome {
        compensateUnknown()
        canvas.markRolledBackWithReason(row.id, clock(), "reinstated")
        return send(row.pipelineId, row.version, row.hash, mode, row.configHash!!, row.planJson!!, newGroupId(), previous = currentDesired(null))
    }

    // ---- the Settings row and the Diagnostics card ----

    suspend fun status(): PlanStatus {
        val row = canvas.activePublishes().firstOrNull { it.component == PlanPublishGroups.COMPONENT && stateOk(it) } ?: return PlanStatus(null, emptyList())
        val g = store.get(row.groupId!!)
        val name = canvas.pipeline(row.pipelineId)?.name ?: row.pipelineId
        val state = g?.state ?: "UNKNOWN"
        val async = canvas.activePublishes().firstOrNull { it.groupId == row.groupId && it.kind == CanvasPublisher.KIND_INTERPRETER }
        return PlanStatus(
            ActivePlan(row.id, row.groupId!!, name, row.version, row.mode.orEmpty(), row.configHash.orEmpty(), state, g?.sentAtMs),
            listOf(
                "Glasses: $state · gen ${g?.gen ?: 0} · ${row.mode.orEmpty().lowercase()}",
                "Phone RT: " + if (state == "EFFECTIVE") "applied" else "waiting for the glasses",
                "ASYNC: " + (async?.mode?.lowercase() ?: "none"),
            ),
        )
    }

    companion object {
        const val NO_CAPTURE_HALF = "This pipeline has no capture half. Publish it as an interpreter."
        const val PUBLISH_FAILED = "Publishing failed. Nothing was changed."
        private val RESTORABLE = setOf(PlanMode.SHADOW.name, PlanMode.TRIAL.name, PlanMode.LIVE.name)
    }
}

/**
 * The phone halves of a group (S25): runs when the glasses report EFFECTIVE (never earlier) and again after a restart (idempotent). [phoneRt] is the phone RT applier seam
 * (Task 13; null = use settings). The ASYNC half publishes as an interpreter row of the group: SHADOW for a SHADOW or TRIAL plan, LIVE only for a LIVE plan (a TRIAL's bound is the
 * glasses' own clock, which an interpreter publish does not have). A block (an untested AI node) leaves the ASYNC half off; Diagnostics shows "ASYNC: none".
 */
class PlanActivation(
    private val canvas: CanvasDao,
    private val publisher: CanvasPublisher,
    private val phoneRt: (String?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun activate(groupId: String, planJson: String?) {
        runCatching { phoneRt(planJson) }
        if (planJson == null) return                                      // use settings: ASYNC is untouched
        val rows = canvas.publishesOfGroup(groupId)
        val plan = rows.firstOrNull { it.component == PlanPublishGroups.COMPONENT && it.rolledBackAt == null } ?: return
        if (rows.any { it.kind == CanvasPublisher.KIND_INTERPRETER }) return
        val full = canvas.version(plan.pipelineId, plan.version)?.let { PipelineJson.decode(it.json).getOrNull() } ?: return
        if (!PlanShape.hasAsyncHalf(full)) return
        publisher.publishInterpreter(plan.pipelineId, plan.version, if (plan.mode == PlanMode.LIVE.name) LiveMode.LIVE else LiveMode.SHADOW, groupId)
    }

    /** The glasses reverted this group: its ASYNC half goes, the plan row is closed, and the phone RT follows whatever plan is still active (or settings). */
    suspend fun deactivate(groupId: String) {
        val rows = canvas.publishesOfGroup(groupId)
        rows.filter { it.kind == CanvasPublisher.KIND_INTERPRETER && it.rolledBackAt == null }.forEach { CanvasPublisher.autoRollbackOn(canvas, clock, it.id, "plan_reverted") }
        rows.filter { it.component == PlanPublishGroups.COMPONENT && it.rolledBackAt == null }.forEach { canvas.markRolledBackWithReason(it.id, clock(), "plan_reverted") }
        val remaining = canvas.activePublishes().firstOrNull { it.component == PlanPublishGroups.COMPONENT && it.planJson != null }
        runCatching { phoneRt(remaining?.planJson) }
    }
}

/**
 * The plan JSON of a publish, keyed by group and publish id, in `canvas_meta` (a plan holds no photo or user data and is at most 8 KB), so a crashed PREPARED group or a compensation
 * can send it again after a restart.
 */
class MetaPlanSource(private val dao: com.rokid.context.memory.db.CanvasSlice3Dao) : PlanSource {
    override suspend fun save(groupId: String, publishId: Long, planJson: String) = dao.putMeta(key(groupId, publishId), planJson)
    override suspend fun load(groupId: String, publishId: Long): String? = dao.meta(key(groupId, publishId))
    private fun key(g: String, id: Long) = "planjson:$g:$id"
}
