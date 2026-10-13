package com.rokid.context.memory

/** Who proposed an action (spec §4.4): stored in `proposal.origin`. */
enum class ProposalOrigin { ASK, NUDGE }

/** `proposal.state` (spec §5.3). Undo is EXECUTED → DECLINED with `resolvedBy = "undo:<surface>"` (no UNDONE state). */
enum class ProposalState { PENDING, EXECUTING, EXECUTED, DECLINED, EXPIRED, SUPPRESSED, FAILED }

/** A wearer's answer to a PENDING proposal. */
enum class Decision { APPROVE, DECLINE }

/** Result of [ProposalRepository.resolve]: exactly one surface wins the transition out of PENDING. */
sealed interface Resolve {
    data class Won(val to: ProposalState) : Resolve
    /** [current] is the state found (null = unknown id); EXPIRED when the answer arrived after `expiresAt`. */
    data class Lost(val current: ProposalState?) : Resolve
}
