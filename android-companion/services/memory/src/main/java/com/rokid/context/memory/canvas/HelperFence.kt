package com.rokid.context.memory.canvas

import com.rokid.context.memory.db.DeletionEpoch

/**
 * What a helper job captured BEFORE its inputs were loaded (core plan section 6, "Frame-Forget fence"): the in-process wipe generation, the persisted
 * deletion epoch and the frame-Forget revision (`frame_forget_rev`, added to canvas_meta by WP3). The DAO re-checks all three in the same transaction at
 * insert, payload release, heartbeat and result commit. Plain data; the checks live in the DAO (WP3).
 */
data class HelperFence(val wipeGen: Long, val deletionEpoch: DeletionEpoch, val frameForgetRev: Long) {
    /** True when nothing was deleted since [this] was captured, judged against the values as they are [now]. */
    fun holds(now: HelperFence): Boolean =
        wipeGen == now.wipeGen && deletionEpoch.sameEpoch(now.deletionEpoch) && frameForgetRev == now.frameForgetRev
}
