package com.rokid.context.memory.forget.lifecycle

/**
 * The limits of a multi-select Forget (Activity pagination design 2.5, proxy ruling P2). One definition for the phone's menu (it disables Forget past the ceiling before planning), the preview and the executor, so all three agree.
 * [ACK_ABOVE]: more roots, or more erased/rebuilt nodes, or more DROP nodes than this need the explicit acknowledgement. [REFUSE_ABOVE]: more roots or plan nodes than this are refused ("Select fewer"); nothing is deleted.
 */
object SelectionLimits {
    const val ACK_ABOVE = 200
    const val REFUSE_ABOVE = 5000
    /** Appended to the plan hash when the user acknowledged a large selection (the same suffix as the scoped Forget's `ackHash`). */
    const val ACK_SUFFIX = "+ack"
}
