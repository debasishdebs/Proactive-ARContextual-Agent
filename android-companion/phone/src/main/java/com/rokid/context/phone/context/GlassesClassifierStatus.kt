package com.rokid.context.phone.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The classifier-threshold hash the glasses last reported on `pc_motion_hint` (v0.9.7 A); null until a hint carries one (an old glasses build never does). */
object GlassesClassifierStatus {
    private val _reported = MutableStateFlow<String?>(null)
    val reported: StateFlow<String?> = _reported

    fun report(hash: String?) { if (hash != null) _reported.value = hash }

    /** "match" (the glasses run what Settings holds), "pending" (they report something else: not yet applied), "unknown" (nothing reported). */
    fun verdict(expected: String, reported: String?): String = when {
        reported == null -> "unknown"
        reported == expected -> "match"
        else -> "pending"
    }
}
