package com.rokid.context.phone.ui.onboarding

import android.content.Context

/** The §2.1 gate. Phase 7's wizard writes the same flag (or bumps [K_COMPLETE] to re-run for Phase-4 users). */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean(K_COMPLETE, false)
    fun markComplete() { prefs.edit().putBoolean(K_COMPLETE, true).apply() }

    companion object {
        private const val PREFS = "onboarding"
        const val K_COMPLETE = "v1_complete"
    }
}
