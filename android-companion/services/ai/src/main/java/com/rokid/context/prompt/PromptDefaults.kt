package com.rokid.context.prompt

import com.rokid.context.ai.contract.PromptKey
import java.security.MessageDigest

/**
 * Earlier shipped defaults, as fingerprints of (seed template, default schema). An install whose active prompt is still
 * byte-identical to one of these was never edited, so [PromptRepository] moves it to the current default.
 */
object PromptDefaults {
    const val UPDATED_NOTE = "Updated default"

    /** ANSWER: the seed before NOTE_ADD ("note that…", user notes), shipped from 2026-10-13. */
    val PREVIOUS: Map<PromptKey, Set<String>> = mapOf(
        PromptKey.ANSWER to setOf("4235a1301a8aad35e73715eef4331716edc854d90159cf64d2a7f0365b40e7a4"),
    )

    /** SHA-256 hex of the template, a NUL, then the schema (empty when none). */
    fun fingerprint(template: String, schema: String?): String =
        MessageDigest.getInstance("SHA-256").digest("$template\u0000${schema.orEmpty()}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
