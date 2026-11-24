package com.rokid.context.phone.ui.shell

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Retention v3 (K5): the one-time notice that clean-up no longer deletes the details of the user's photos (a semantic change to a privacy setting must not be silent). [pending] is true until the user dismisses it; the
 * dismissal is stored in a small prefs file (`retention_notice`, no user data) and the notice never returns. A fresh install shows it once too (it is harmless and keeps the rule simple).
 */
interface RetentionNoticeControl {
    val pending: Flow<Boolean>
    fun dismiss()

    companion object {
        val None: RetentionNoticeControl = object : RetentionNoticeControl {
            override val pending: Flow<Boolean> = flowOf(false)
            override fun dismiss() = Unit
        }
        const val PREFS = "retention_notice"
        const val KEY = "v3_dismissed"

        fun prefs(context: Context): RetentionNoticeControl {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val state = MutableStateFlow(!runCatching { p.getBoolean(KEY, false) }.getOrDefault(false))
            return object : RetentionNoticeControl {
                override val pending: Flow<Boolean> = state
                override fun dismiss() { runCatching { p.edit().putBoolean(KEY, true).apply() }; state.value = false }
            }
        }
    }
}
