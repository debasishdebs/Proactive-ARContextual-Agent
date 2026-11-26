package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.orchestration.forget.ForgetActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Place object S3, mode (b): "the place and its N Moments" (design section 3). No new Forget engine: the roots are a PHOTO Ref for every live photo of each of the place's Moments plus a MARK Ref for every mark of those
 * seconds, handed to the multi-select Forget ([ForgetActions.previewSelection] / [ForgetActions.forgetSelectionOutcome], so the DataOpGate, the 1.4.2 pin arming and the post-commit cleanup are the ones every Forget uses).
 * Only when `forgetV2` is ON (otherwise [preview] is null and the dialog hides the choice). Mode (a) (the pin, its caption and card history, every Moment stays) runs only AFTER the Forget COMMITTED, and only if the pin is
 * still there (the 1.4.2 cascade removes it when its reference Moment loses its last photo). Nothing beyond the confirmed set is deleted. Logs are counts and literals only.
 */
object PlaceDelete {
    private const val TAG = "PlaceDelete"

    /** What mode (b) would do, for the dialog. [otherPinNames] = the OTHER pins the Forget deletes with it (1.4.2). [needsAck] above 200 roots/nodes; [tooLarge] above 5,000 (refused). Holds the roots only to run exactly this set. */
    class Preview internal constructor(
        val pinId: String, val name: String, val moments: Int, val photos: Int, val memories: Int, val marks: Int,
        val otherPinNames: List<String>, val needsAck: Boolean, val tooLarge: Boolean,
        internal val roots: Set<Ref>, internal val selection: SelectionPreview,
    ) {
        /** The token the delete is confirmed with (the acknowledgement only when needed AND given). */
        fun token(acknowledged: Boolean): String = selection.token(acknowledged)
    }

    /** [outcome] of the Forget; [pinDeleted] = mode (a) ran and the pin is gone (also true when the Forget itself already removed it). */
    class Result(val outcome: ForgetActions.Outcome, val pinDeleted: Boolean)

    /** The Forget roots of the Moments [momentIds]: live photos and marks of each second. Moments with neither are skipped; returns (Moments with roots, roots). */
    internal suspend fun rootsOf(context: Context, momentIds: Collection<String>): Pair<Int, Set<Ref>> {
        val db = MemoryGraph.get(context).db
        val roots = LinkedHashSet<Ref>(); var moments = 0
        for (id in momentIds.distinct()) {
            val sec = MomentId.parse(id) ?: continue
            val from = sec * 1000; val to = from + 999
            val photos = db.forgetDao().frameIdsBetween(from, to)
            val marks = db.canvasSlice3Dao().labelsBetween(from, to).filter { it.kind == "MOMENT" }.map { it.labelId }
            if (photos.isEmpty() && marks.isEmpty()) continue
            moments++
            photos.forEach { roots += Ref(Family.PHOTO, it) }; marks.forEach { roots += Ref(Family.MARK, it) }
        }
        return moments to roots
    }

    /**
     * The preview of mode (b) for [pinId]; null = the choice is not offered (`forgetV2` OFF, unknown pin, 0 Moments, or any failure). Read only, no gate. A selection above 5,000 comes back with [Preview.tooLarge] (shown, refused).
     */
    suspend fun preview(context: Context, pinId: String): Preview? = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val pin = PlaceGraph.pins(app).pins().firstOrNull { it.id == pinId } ?: return@withContext null
            val (n, roots) = rootsOf(app, PlaceMoments.of(app, pinId))
            if (n == 0 || roots.isEmpty()) return@withContext null
            val sel = ForgetActions.previewSelection(app, roots) ?: return@withContext null // null = forgetV2 OFF or no plan
            val others = sel.pinNames.toMutableList()
            if (pinId in sel.pinIds) others.remove(pin.name) // this pin is the one being deleted anyway
            val otherCount = (sel.pinIds - pinId).size
            Preview(pinId, pin.name, n, sel.count(Family.PHOTO), sel.count(Family.MEMORY), sel.count(Family.MARK), if (otherCount == 0) emptyList() else others, sel.needsAck, sel.tooLarge, roots, sel)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: Exception) { PcLog.w(TAG, "place delete preview failed: ${SafeLog.describe(e)}"); null }
    }

    /**
     * Runs mode (b) exactly as previewed. [deletePin] is mode (a) for the pin (the screen's `PlacePinning.delete`), called only when the Forget COMMITTED and the pin still exists. A preview above 5,000 is refused
     * ([ForgetActions.Outcome.TOO_LARGE]); above 200 without the acknowledgement nothing is deleted ([ForgetActions.Outcome.CONTENT_CHANGED]).
     */
    suspend fun run(context: Context, preview: Preview, acknowledged: Boolean, deletePin: suspend (String) -> Boolean): Result {
        val app = context.applicationContext
        if (preview.tooLarge) return Result(ForgetActions.Outcome.TOO_LARGE, false)
        if (preview.needsAck && !acknowledged) return Result(ForgetActions.Outcome.CONTENT_CHANGED, false)
        val outcome = ForgetActions.forgetSelectionOutcome(app, preview.roots, preview.token(acknowledged), onForgotten = ForgetActions.dataRemoved, pins = preview.selection.allowedPins)
        if (!outcome.committed) return Result(outcome, false)
        val stillThere = runCatching { PlaceGraph.pins(app).pins().any { it.id == preview.pinId } }.getOrDefault(true)
        val gone = if (!stillThere) true else try { deletePin(preview.pinId) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { false }
        PcLog.i(TAG, "place delete cascade done pinDeleted=$gone")
        return Result(outcome, gone)
    }
}
