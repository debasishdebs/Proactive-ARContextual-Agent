package com.rokid.context.prompt

import com.rokid.context.ai.contract.PromptKey
import java.security.MessageDigest

/**
 * Earlier shipped defaults, as fingerprints of (seed template, default schema). An install whose active prompt is still
 * byte-identical to one of these was never edited, so [PromptRepository] moves it to the current default.
 */
object PromptDefaults {
    const val UPDATED_NOTE = "Updated default"

    /**
     * ANSWER: the seed before NOTE_ADD ("note that…", user notes), shipped from 2026-10-13.
     * VISION_EXTRACT: the seed and FACTS schema before the provenance contract (source_frame_ids + captions), shipped in v1-beta.
     */
    val PREVIOUS: Map<PromptKey, Set<String>> = mapOf(
        PromptKey.ANSWER to setOf("4235a1301a8aad35e73715eef4331716edc854d90159cf64d2a7f0365b40e7a4"),
        PromptKey.VISION_EXTRACT to setOf("5514441bfd6ef6b92723afb143e91c9d62c4f165387718c36c1b12d77d0c8656"),
    )

    /** SHA-256 hex of the template, a NUL, then the schema (empty when none). */
    fun fingerprint(template: String, schema: String?): String =
        MessageDigest.getInstance("SHA-256").digest("$template\u0000${schema.orEmpty()}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
