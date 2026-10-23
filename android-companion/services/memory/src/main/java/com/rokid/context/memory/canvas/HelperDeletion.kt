package com.rokid.context.memory.canvas

import com.rokid.context.memory.db.CanvasSlice3Dao

/**
 * Why open helper jobs were ended by something other than the user's decline or a timeout (core plan section 6, "deletion causes"). [code] is the opaque reason stored as
 * the job's `voidReason` and in its cleanup row: a code, never an id or user data.
 */
enum class HelperDeletionCause(val code: String) {
    /** A frame or a time range was forgotten (`forgetRangeTx`, `deleteSliceOutputsForFrames`): those entry points do this inside their own transaction. */
    FRAME_FORGET("frame_forget"),
    /** Delete all (`WipeDao.wipeMemory`): it deletes the job rows and writes the wildcard cleanup row inside its own transaction. */
    DELETE_ALL("delete_all"),
    GRANT_OFF("grant_off"),
    DISCONNECT("disconnect"),
    DEVELOPER_MODE_OFF("developer_off"),
    /** Session end for any other reason (inactivity expiry, LAN loss, notification Stop, idle stop, service shutdown): WP6's `HelperDevSession.terminate(cause)`. */
    SESSION_END("session_end"),
    RESTART("restart"),
}

/**
 * The seam between the deletion hooks (here, `:services:memory`) and the helper credentials that live in `:phone` (WP2: `HelperGrants` prefs `canvas_helper_grants`, `HelperAuth`).
 * `:services:memory` and `:services:orchestration` never depend on `:phone`, so WP6 binds this interface to those classes when it wires `HelperDevSession`. Both calls must be
 * idempotent and cheap (they run on every end of a session) and must not need the database.
 */
interface HelperSessionHooks {
    /** Turns every helper capability grant off (WP2: `HelperGrants.setGrant(py_exec, false)`), so no new job can be accepted or confirmed. */
    fun grantsOff()

    /** Revokes the helper token and the memory-only confirmation key (WP2: `HelperAuth` revoke), so the helper's next request is a 401 and no tag can be minted. */
    fun revokeHelper()

    /** What an app without a helper binds (tests, the build before WP6). */
    object NONE : HelperSessionHooks {
        override fun grantsOff() {}
        override fun revokeHelper() {}
    }
}

/**
 * The helper-job half of every deletion cause that is NOT a Forget or Delete all (those two do the same inside their own database transaction: `forgetRangeTx`,
 * `deleteSliceOutputsForFrames`, `WipeDao.wipeMemory`). Each function first runs the credential hooks (so nothing new can be leased or confirmed), then, whatever the hooks did
 * (fail closed: a throwing hook never leaves a job alive), voids EVERY open job, also one still awaiting its tap, in one database transaction that drops its payload and writes
 * its cleanup row (the cleanup table survives Delete all and a restart). A tap after any of them is refused because the job is no longer awaiting. Returns the jobs ended; a hook
 * failure is rethrown after the jobs were ended.
 */
class HelperDeletion(
    private val dao: CanvasSlice3Dao,
    private val hooks: HelperSessionHooks = HelperSessionHooks.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The `py_exec` grant was turned off. */
    suspend fun grantOff(): Int = run(HelperDeletionCause.GRANT_OFF, { hooks.grantsOff() })

    /** The user chose Disconnect (all helpers): the token is revoked. */
    suspend fun disconnectAll(): Int = run(HelperDeletionCause.DISCONNECT, { hooks.revokeHelper() })

    /** Any session end: grant off, token revoked (in that order), then every open job voided with [cause] as the reason. */
    suspend fun endSession(cause: HelperDeletionCause): Int = run(cause, { hooks.grantsOff() }, { hooks.revokeHelper() })

    /** Process start: every open job belonged to a dead process (tokens and confirmations are memory-only): ABORTED, payload dropped, cleanup enqueued. */
    suspend fun restart(): Int = dao.abortOpenHelperJobs(clock())

    /** Every step runs even if an earlier one threw (a failed grant write must not leave the token valid), then the jobs are voided; the FIRST failure is rethrown, later ones attached. */
    private suspend fun run(cause: HelperDeletionCause, vararg steps: () -> Unit): Int {
        var failure: Throwable? = null
        for (step in steps) try { step() } catch (e: Throwable) { failure?.addSuppressed(e) ?: run { failure = e } }
        val ended = try { dao.voidAllHelperJobs(cause.code, clock()) } catch (e: Throwable) { failure?.let(e::addSuppressed); throw e }
        failure?.let { throw it }
        return ended
    }
}
