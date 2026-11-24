package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.phone.ui.list.ListKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The Forget behind a selection (production: [ForgetActions]); a test passes a fake. Null port = the feature is OFF and the selection UI does not exist. */
interface SelectionForgetPort {
    suspend fun preview(roots: Set<Ref>): SelectionPreview?
    suspend fun forget(roots: Set<Ref>, token: String): ForgetActions.Outcome
    /** The confirmed Forget bound to the pins the dialog named ([pins]; empty = none). A port that knows no pins ignores them. */
    suspend fun forget(roots: Set<Ref>, token: String, pins: Set<String>): ForgetActions.Outcome = forget(roots, token)
}

/** Where the multi-select Forget is: nothing, planning, asking the user, refused, or deleting. */
sealed interface ForgetStage {
    data object Idle : ForgetStage
    data object Planning : ForgetStage
    /** [preview] is the planner's plan; [acknowledged] is the user's tick for a large one; [changed] says the set moved since the last preview (fresh counts shown). */
    data class Confirm(val preview: SelectionPreview, val acknowledged: Boolean = false, val changed: Boolean = false) : ForgetStage {
        /** The Forget button is enabled: always, except a large selection that is not acknowledged yet. */
        val canConfirm: Boolean get() = !preview.needsAck || acknowledged
    }
    data class Refused(val reason: Reason, val count: Int) : ForgetStage
    data object Running : ForgetStage
    enum class Reason { CEILING, TOO_LARGE }
}

/** [message] is a one-shot fixed text (never a user's content) the screen toasts. */
data class SelectionState(val selection: Selection = Selection(), val stage: ForgetStage = ForgetStage.Idle, val message: String? = null)

/** The fixed words of selection mode (tests and screens read the same). */
object SelectionText {
    fun selected(n: Int) = if (n == 1) "1 selected" else "$n selected"
    fun refusedCeiling(n: Int) = "Too many to forget at once ($n selected). Select fewer than ${SelectionLimits.REFUSE_ABOVE + 1}."
    const val REFUSED_TOO_LARGE = "Too much is connected to forget in one step. Select fewer."
    const val CHANGED = "What this removes changed since you looked. Check the new counts."
    const val NOTHING_LEFT = "Nothing left to forget. It was already forgotten."
    const val BUSY = "Another operation is running. Try again in a moment."
    const val FAILED = "Couldn't forget the selection. Nothing was changed."
    const val PREVIEW_FAILED = "Couldn't check what this would remove. Nothing was changed."
    const val TOO_MANY_HERE = "More than 5,000 here. Select fewer."
    fun forgot(n: Int) = if (n == 1) "Forgot 1 item" else "Forgot $n items"
    fun ack(n: Int) = "I understand this removes or rebuilds about $n items"
}

/**
 * Multi-select Forget of one list (activity pagination A7/A8, proxy ruling P2). The selection is a set of refs held HERE, not in the rows: paging, collapsing and view switches never touch it; it is saved
 * ([store]) so a process death keeps it. "Select this hour/day" resolves an ids-only, kind-scoped set. Forget = `preview` (the planner's plan), the user's confirmation (an acknowledgement above
 * [SelectionLimits.ACK_ABOVE]), then `forget` with the preview's token; a changed set re-opens the confirmation with fresh counts; above [SelectionLimits.REFUSE_ABOVE] it is refused before planning.
 * [onForgotten] runs ONCE after a committed Forget (the list's single refresh).
 */
class SelectionController(
    private val kind: ListKind, private val resolver: SelectionResolver, private val port: () -> SelectionForgetPort?, private val store: StateStore,
    private val scope: CoroutineScope, private val onForgotten: () -> Unit,
) {
    private val _state = MutableStateFlow(SelectionState(Selection.decode(store.get(KEY))))
    val state: StateFlow<SelectionState> get() = _state

    private fun set(f: (SelectionState) -> SelectionState) { _state.value = f(_state.value); store.set(KEY, _state.value.selection.encode()) }
    private fun edit(f: (Selection) -> Selection) = set { it.copy(selection = f(it.selection)) }

    /** Whether this list offers selection at all: the feature is ON and the kind can be forgotten. */
    val available: Boolean get() = port() != null && SelectionKinds.supports(kind)

    fun enter(id: String) { if (!available) return; SelectionKinds.ref(kind, id)?.let { r -> edit { it.enter(r) } } }
    fun toggle(id: String) { if (!available) return; SelectionKinds.ref(kind, id)?.let { r -> edit { it.toggle(r) } } }
    /** "Select this page": the rows on screen. */
    fun selectPage(ids: Collection<String>) { if (available) edit { it.addAll(SelectionKinds.refs(kind, ids)) } }
    fun clear() = set { it.copy(selection = Selection(), stage = ForgetStage.Idle) }

    fun selectDay(day: LocalDate) = resolve { resolver.day(day) }
    fun selectHour(day: LocalDate, hour: HourNode) = resolve { resolver.hour(day, hour) }
    private fun resolve(read: suspend () -> Resolved) {
        if (!available) return
        scope.launch {
            when (val r = read()) {
                is Resolved.Ids -> edit { it.addAll(r.refs) }
                Resolved.TooMany -> set { it.copy(message = SelectionText.TOO_MANY_HERE) }
            }
        }
    }

    fun consumeMessage() = set { it.copy(message = null) }

    // ---- Forget ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** "Forget selected (N)": refuses above the ceiling before planning, else plans and asks. */
    fun forgetSelected() {
        val p = port() ?: return
        val sel = _state.value.selection
        if (sel.size == 0 || _state.value.stage != ForgetStage.Idle) return
        if (sel.overCeiling) { set { it.copy(stage = ForgetStage.Refused(ForgetStage.Reason.CEILING, sel.size)) }; return }
        scope.launch { plan(p, sel.refs, changed = false) }
    }

    private suspend fun plan(p: SelectionForgetPort, roots: Set<Ref>, changed: Boolean) {
        set { it.copy(stage = ForgetStage.Planning) }
        val preview = try { p.preview(roots) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        when {
            preview == null -> set { it.copy(stage = ForgetStage.Idle, message = SelectionText.PREVIEW_FAILED) }
            preview.tooLarge -> set { it.copy(stage = ForgetStage.Refused(ForgetStage.Reason.TOO_LARGE, roots.size)) }
            else -> {
                // refs the plan did not find were forgotten elsewhere meanwhile: they leave the selection
                if (preview.missing.isNotEmpty()) edit { it.prune(preview.missing) }
                if (preview.nodes == 0) set { it.copy(selection = it.selection.clear(), stage = ForgetStage.Idle, message = SelectionText.NOTHING_LEFT) }
                else set { it.copy(stage = ForgetStage.Confirm(preview, acknowledged = false, changed = changed)) }
            }
        }
    }

    fun acknowledge(on: Boolean) = set { s -> (s.stage as? ForgetStage.Confirm)?.let { s.copy(stage = it.copy(acknowledged = on)) } ?: s }
    fun dismiss() = set { it.copy(stage = ForgetStage.Idle) }

    /** The user pressed Forget in the confirmation. */
    fun confirm() {
        val p = port() ?: return
        val stage = _state.value.stage as? ForgetStage.Confirm ?: return
        if (!stage.canConfirm) return
        val roots = _state.value.selection.refs
        val token = stage.preview.token(stage.acknowledged)
        set { it.copy(stage = ForgetStage.Running) }
        scope.launch {
            val out = try { p.forget(roots, token, stage.preview.allowedPins) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ForgetActions.Outcome.FAILED }
            when (out) {
                ForgetActions.Outcome.DONE, ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.Outcome.PIN_CLEANUP_PENDING, ForgetActions.Outcome.PIN_NOT_REMOVED, ForgetActions.Outcome.CACHE_AND_PIN_PENDING, ForgetActions.Outcome.CACHE_AND_PIN_NOT_REMOVED -> {
                    set { it.copy(selection = Selection(), stage = ForgetStage.Idle, message = when (out) { ForgetActions.Outcome.PIN_CLEANUP_PENDING -> com.rokid.context.phone.ui.memory.ForgetCopy.pinNotice(true, false); ForgetActions.Outcome.PIN_NOT_REMOVED -> com.rokid.context.phone.ui.memory.ForgetCopy.pinNotice(false, false); ForgetActions.Outcome.CACHE_AND_PIN_PENDING -> com.rokid.context.phone.ui.memory.ForgetCopy.pinNotice(true, true); ForgetActions.Outcome.CACHE_AND_PIN_NOT_REMOVED -> com.rokid.context.phone.ui.memory.ForgetCopy.pinNotice(false, true); else -> SelectionText.forgot(roots.size) }) }
                    onForgotten() // ONE refresh after the receipt
                }
                ForgetActions.Outcome.CONTENT_CHANGED -> plan(p, roots, changed = true)
                ForgetActions.Outcome.TOO_LARGE -> set { it.copy(stage = ForgetStage.Refused(ForgetStage.Reason.TOO_LARGE, roots.size)) }
                ForgetActions.Outcome.EXPORT_IN_PROGRESS, ForgetActions.Outcome.SAVE_IN_PROGRESS, ForgetActions.Outcome.UPLOAD_IN_PROGRESS -> set { it.copy(stage = ForgetStage.Idle, message = SelectionText.BUSY) }
                ForgetActions.Outcome.PIN_PREP_FAILED -> set { it.copy(stage = ForgetStage.Idle, message = com.rokid.context.phone.ui.memory.ForgetCopy.PIN_PREP_FAILED) }
                ForgetActions.Outcome.PINS_NOT_CONFIRMED -> set { it.copy(stage = ForgetStage.Idle, message = com.rokid.context.phone.ui.memory.ForgetCopy.PINS_CHANGED) }
                ForgetActions.Outcome.FAILED -> set { it.copy(stage = ForgetStage.Idle, message = SelectionText.FAILED) }
            }
        }
    }

    companion object { const val KEY = "list.selection" }
}
