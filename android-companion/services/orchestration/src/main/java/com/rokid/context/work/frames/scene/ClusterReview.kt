package com.rokid.context.work.frames.scene

import com.rokid.context.ai.contract.ClusterOutput
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.ExtractFollowUp

/**
 * The review verdict for a cluster call's facts: wave 1's free LOCAL critic only (no model critic per cluster, cost). An unsure
 * verdict saves the facts NEEDS_REVIEW and the caller passes [trusted] = false to `ExtractFollowUp.afterSave`, which queues wave 1's
 * PRIORITY_REPROCESS (the existing review path).
 */
class ClusterReview(val trusted: Boolean, val confidence: Double) {
    val factReview: ReviewState get() = if (trusted) ReviewState.OK else ReviewState.NEEDS_REVIEW

    companion object {
        fun of(out: ClusterOutput, id: String): ClusterReview {
            val facts = out.clusters.flatMap { it.facts }.filter { it.text.isNotBlank() }
            return ClusterReview(ExtractFollowUp.locallyTrusted(CriticWorker(), id, facts), ExtractFollowUp.confidence(facts))
        }
    }
}
