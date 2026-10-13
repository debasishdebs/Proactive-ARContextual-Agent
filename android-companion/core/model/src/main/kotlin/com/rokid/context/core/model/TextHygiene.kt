package com.rokid.context.core.model

/**
 * Shared text clean-up patterns (Phase 6, preflight F14). [SEPARATORS] is the one set that collapses to a single space
 * in voice transcripts, typed asks and prompt text: whitespace, control characters, NEL, LINE and PARAGRAPH SEPARATOR.
 * Plain JVM; ICU-safe (no `}` and no `&&` in the class).
 */
object TextHygiene {
    val SEPARATORS = Regex("[\\s\\p{Cntrl}\\u0085\\u2028\\u2029]+")
}
