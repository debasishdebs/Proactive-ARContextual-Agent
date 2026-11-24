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
     * ANSWER: the seed before NOTE_ADD ("note that…", user notes), shipped from 2026-10-13; the NOTE_ADD seed (v1.0, without `{{now}}`, the time-window
     * note and the last-seen-state rule) is the second entry; the seed with the unqualified last-state rule is the third.
     * VISION_EXTRACT: the seed and FACTS schema before the provenance contract (source_frame_ids + captions), shipped in v1-beta.
     */
    val PREVIOUS: Map<PromptKey, Set<String>> = mapOf(
        PromptKey.ANSWER to setOf(
            "4235a1301a8aad35e73715eef4331716edc854d90159cf64d2a7f0365b40e7a4",
            "ee96b07885414eccb3067b4f1623df27a5a6d00725a6bea33797d2c5dc8c1dc1",
            "81a974f69c5ab1cdbfa03c93cc5157b08c5c18d00a8d20c8c922bbcef0e4d2a4", // the v1.0.1 seed whose rule 3a asserted "the last state you saw" (retrieval is by relevance, not recency)
        ),
        PromptKey.VISION_EXTRACT to setOf(
            "5514441bfd6ef6b92723afb143e91c9d62c4f165387718c36c1b12d77d0c8656",
            "df262c3711776a9a2a48b6f7b5a433ebceb4b0328b7879533a7b75bac1d4d8da", // the seed before the sign-text rule (F5a)
        ),
    )

    /** SHA-256 hex of the template, a NUL, then the schema (empty when none). */
    fun fingerprint(template: String, schema: String?): String =
        MessageDigest.getInstance("SHA-256").digest("$template\u0000${schema.orEmpty()}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
