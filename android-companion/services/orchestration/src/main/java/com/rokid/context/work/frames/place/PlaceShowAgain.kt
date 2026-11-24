package com.rokid.context.work.frames.place

import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.CancellationException

/**
 * "Show on glasses again" of a history row (spec 2026-11-24 B2), fenced against Forget and Delete all (Codex place fixes r1 P1-3).
 *
 * The authority is captured BEFORE anything suspends: the holder's epoch (every withdrawal advances it) and the wipe generation. After the rebuild's reads (which suspend) the row, its provenance and both
 * authorities are asked AGAIN, and the card is published with the CAPTURED epoch, so a Forget or Delete all that ran during the reads can never be followed by the card. The history row of the new card is
 * inserted by the guarded [PlaceCardHistory.add] (authority captured at the start); if a Forget or Delete all came in between after the card was shown, the card is withdrawn again.
 */
class PlaceShowAgain(
    private val holder: PlaceCardHolder,
    private val history: () -> PlaceCardHistory,
    /** The pack/pin places the rebuild reads (suspends). */
    private val places: suspend (PlaceCardLogEntity) -> List<PlaceEntity>,
    private val generation: () -> Long,
    /** True while the row still exists and its photo (if it names one) is not forgotten. Suspends (Room). */
    private val rowAlive: suspend (PlaceCardLogEntity) -> Boolean,
    /** The zone fence's own word, read again at the moment of publication. */
    private val fenceBlocked: () -> Boolean = { false },
) {
    suspend fun run(row: PlaceCardLogEntity): Boolean {
        val epoch = holder.epoch(); val gen = generation() // captured first: nothing below may take a newer one
        val authority = try { history().capture() } catch (c: CancellationException) { throw c } catch (_: Exception) { return false } // CH 6: the history authority is captured at the START, before the rebuild's reads
        val rebuilt = PlaceCardRebuild.card(row, places(row)) ?: return false
        if (!rowAlive(row)) return false
        if (generation() != gen || holder.epoch() != epoch || fenceBlocked()) return false
        val id = holder.publishIfId(rebuilt, epoch) ?: return false // the epoch check is in the same synchronous step as the publication
        val kind = runCatching { HistoryKind.valueOf(row.kind) }.getOrDefault(HistoryKind.PHOTO)
        val rowId = try {
            // the guarded insert refuses the row when a Forget, Delete all or fence moved since [authority] was captured
            history().add(PlaceHistoryEvent(kind = kind, placeId = row.placeId, title = row.title, members = PlaceCardRebuild.members(row), frameId = row.frameId, cardId = id, authority = authority))
        } catch (c: CancellationException) { holder.clearIf(id); throw c }
        // the card's own publication advanced the epoch by none (publishIfId does not): any later advance is a withdrawal
        if (!(rowAlive(row) && generation() == gen && holder.epoch() == epoch)) { holder.clearIf(id); rowId?.let { history().delete(it) }; return false } // a Forget or Delete all came first: no card and no row
        return true
    }
}
