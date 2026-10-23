package com.rokid.context.memory.canvas

/** Persisted state of a helper job (core plan section 6). Every CORE `py_exec` job is inserted as [AWAITING_CONFIRM]. */
enum class HelperJobState(val open: Boolean) {
    AWAITING_CONFIRM(true), QUEUED(true), LEASED(true),
    DONE(false), FAILED(false), CANCELLED(false), VOIDED(false), ABORTED(false);

    /** The state after [event], or null when the event is refused in this state. Terminal states accept nothing. */
    fun next(event: JobEvent): HelperJobState? = when (this) {
        AWAITING_CONFIRM -> when (event) {
            JobEvent.CONFIRM -> QUEUED
            JobEvent.DECLINE, JobEvent.CANCEL -> CANCELLED
            JobEvent.EXPIRE -> FAILED
            JobEvent.VOID -> VOIDED
            JobEvent.ABORT -> ABORTED
            else -> null
        }
        QUEUED -> when (event) {
            JobEvent.LEASE -> LEASED
            JobEvent.CANCEL -> CANCELLED
            JobEvent.EXPIRE -> FAILED
            JobEvent.VOID -> VOIDED
            JobEvent.ABORT -> ABORTED
            else -> null
        }
        LEASED -> when (event) {
            JobEvent.HEARTBEAT -> LEASED
            JobEvent.RESULT -> DONE
            JobEvent.FAIL, JobEvent.LEASE_EXPIRE, JobEvent.EXPIRE -> FAILED
            JobEvent.CANCEL -> CANCELLED
            JobEvent.VOID -> VOIDED
            JobEvent.ABORT -> ABORTED
            else -> null
        }
        DONE, FAILED, CANCELLED, VOIDED, ABORTED -> null
    }
}

/** The only things that move a job between states. [CONFIRM] is the only way out of [HelperJobState.AWAITING_CONFIRM] towards the queue. */
enum class JobEvent { CONFIRM, DECLINE, EXPIRE, CANCEL, VOID, ABORT, LEASE, HEARTBEAT, RESULT, FAIL, LEASE_EXPIRE }
