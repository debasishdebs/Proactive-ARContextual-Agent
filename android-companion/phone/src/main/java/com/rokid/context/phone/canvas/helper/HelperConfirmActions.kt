package com.rokid.context.phone.canvas.helper

/** One job waiting for the user's tap (what the card lists): input kind, item count, source size and the tap window's end. No source text, no hashes, no frame ids. */
data class PendingConfirmRow(val jobId: String, val inputKind: String, val itemCount: Int, val sourceChars: Int, val expiresAtMs: Long)

/**
 * The only thing the native confirm card holds (user decision 2026-10-23 evening): the user's tap on one pending job. WP6's `HelperConfirmAdapter` implements it by
 * minting the `HelperConfirm` tag and calling the job port; the card, the view model and the browser never see the key, a tag or a hash. [pending] is a cheap
 * snapshot (WP6 backs it with its own state); an entry past its expiry is dropped by the view model.
 */
interface HelperConfirmActions {
    fun onConfirm(jobId: String)
    fun onCancel(jobId: String)
    fun pending(): List<PendingConfirmRow>

    companion object {
        /** The inert default (no job is ever pending): the production host until WP6 binds the adapter. */
        val NONE: HelperConfirmActions = object : HelperConfirmActions {
            override fun onConfirm(jobId: String) {}
            override fun onCancel(jobId: String) {}
            override fun pending(): List<PendingConfirmRow> = emptyList()
        }
    }
}
