package com.rokid.context.memory.forget.lifecycle

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ForgetTooLarge
import java.security.MessageDigest

/** How many nodes of one family a plan erases, rebuilds or drops: what the confirmation shows. Counts only. */
data class FateCounts(val erase: Int, val rebuild: Int, val drop: Int) { val total: Int get() = erase + rebuild + drop }

/**
 * What a multi-select Forget would do (activity pagination A8, ruling P2), from the SAME [ForgetPlan] the executor re-plans inside the deleting transaction: [counts] per family (the dependency preview), the
 * [roots] asked for, the [missing] roots the plan did not find (already forgotten elsewhere), whether the selection [needsAck] (above [SelectionLimits.ACK_ABOVE]) or is [tooLarge] (refused, nothing can be deleted).
 * [token] is what confirms it: the [planHash], or the [ackHash] (`hash+ack`) once the user acknowledged a large selection. Ids and counts only, never a text.
 */
class SelectionPreview(
    val roots: Int, val missing: Set<Ref>, val nodes: Int, val counts: Map<Family, FateCounts>, val planHash: String, val needsAck: Boolean, val tooLarge: Boolean,
    /** Slice 1.4.2: the photos the plan erases (frame ids only), so the caller can tell which Moments (and the pins that reference them) lose their last photo. Never shown. */
    val erasedPhotos: Set<String> = emptySet(),
    /** Slice 1.4.2: pinned places that reference a Moment this Forget removes (deleted with it): their number and the first names, for the dialog only. */
    val pins: Int = 0, val pinNames: List<String> = emptyList(),
    /** The ids of exactly the pins named in the dialog, and whether the pin lookup FAILED (unknown): the Forget arms only [allowedPins]. */
    val pinIds: Set<String> = emptySet(), val pinsFailed: Boolean = false,
) {
    fun withPins(count: Int, names: List<String>, ids: Set<String> = emptySet(), failed: Boolean = false) = SelectionPreview(roots, missing, nodes, counts, planHash, needsAck, tooLarge, erasedPhotos, count, names, ids, failed)
    /** The pins this confirmation may delete: exactly the named ones, none when the lookup failed. */
    val allowedPins: Set<String> get() = if (pinsFailed) emptySet() else pinIds
    val ackHash: String get() = if (needsAck) planHash + SelectionLimits.ACK_SUFFIX else planHash
    /** The token the delete is confirmed with: the acknowledgement only when one is needed AND given. */
    fun token(acknowledged: Boolean): String = if (needsAck && acknowledged) ackHash else planHash
    fun count(f: Family): Int = counts[f]?.total ?: 0
}

/** The rules shared by the preview and the executor, so a confirmed token means exactly what the delete does ("preview == delete"). */
object ForgetSelection {
    private const val PLAN_VERSION = 2

    /** The hash of the roots and the whole plan (every node with its fate). A different selection, or a changed plan (a new memory, a new answer), is a different hash. */
    fun hashOf(roots: Set<Ref>, plan: ForgetPlan): String {
        val text = "v=$PLAN_VERSION;roots=" + roots.map { it.toString() }.sorted().joinToString(",") + ";plan=" + plan.renderWithContent()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }

    fun tooLarge(roots: Set<Ref>, plan: ForgetPlan): Boolean = roots.size > SelectionLimits.REFUSE_ABOVE || plan.size > SelectionLimits.REFUSE_ABOVE
    /** More than [SelectionLimits.ACK_ABOVE] roots, or erased/rebuilt nodes, or dropped (regenerable) nodes. */
    fun needsAck(roots: Set<Ref>, plan: ForgetPlan): Boolean {
        val dropped = plan.nodes.values.count { it == Fate.DROP }
        return roots.size > SelectionLimits.ACK_ABOVE || plan.size - dropped > SelectionLimits.ACK_ABOVE || dropped > SelectionLimits.ACK_ABOVE
    }

    fun preview(roots: Set<Ref>, plan: ForgetPlan): SelectionPreview {
        val counts = plan.nodes.entries.groupBy { it.key.family }.mapValues { (_, e) -> FateCounts(e.count { it.value == Fate.ERASE }, e.count { it.value == Fate.REBUILD }, e.count { it.value == Fate.DROP }) }
        return SelectionPreview(roots.size, roots - plan.nodes.keys, plan.size, counts, hashOf(roots, plan), needsAck(roots, plan), tooLarge(roots, plan),
            erasedPhotos = plan.nodes.entries.filter { it.key.family == Family.PHOTO && it.value == Fate.ERASE }.map { it.key.id }.toSet())
    }

    /** Reads only: the plan of [roots] as a user Forget, in one snapshot. A selection above the ceiling, or a closure the planner refuses, is a preview with [SelectionPreview.tooLarge] and nothing else. */
    suspend fun preview(db: ContextDatabase, roots: Set<Ref>): SelectionPreview {
        if (roots.size > SelectionLimits.REFUSE_ABOVE) return SelectionPreview(roots.size, emptySet(), 0, emptyMap(), "", needsAck = false, tooLarge = true)
        return try { preview(roots, db.withTransaction { ForgetPlanner(db).plan(roots, Policy.User) }) } catch (e: ForgetTooLarge) { SelectionPreview(roots.size, emptySet(), 0, emptyMap(), "", needsAck = false, tooLarge = true) }
    }
}
