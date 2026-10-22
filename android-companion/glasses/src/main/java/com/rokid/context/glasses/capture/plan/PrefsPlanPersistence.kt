package com.rokid.context.glasses.capture.plan

import android.content.SharedPreferences
import com.rokid.context.capture.plan.PlanPersistence
import com.rokid.context.capture.plan.ReceiverState
import com.rokid.context.capture.plan.ReceiverStateJson

/**
 * The plan and the receiver state in the `capture_plan` prefs (one string). Excluded from backup and device transfer like every
 * glasses pref (GlassesBackupRulesTest). [commit] is synchronous (`commit()`, not `apply()`): the receiver persists BEFORE it acks, and
 * a failed write throws so the controller never reports EFFECTIVE for a state that is not on disk. Reads happen once, at load.
 */
class PrefsPlanPersistence(private val prefs: SharedPreferences) : PlanPersistence {
    override fun load(): ReceiverState? {
        val raw = prefs.getString(KEY, null) ?: return null
        ReceiverStateJson.decode(raw)?.let { return it }
        // Unreadable state. Fail closed: the newest gen ever held (written in the same edit as the state) is treated as REVERTED, so a
        // re-sent gen is never applied again; a newer gen is accepted as usual. No high-water mark = nothing to protect = no plan.
        val install = prefs.getString(HWM_INSTALL, null) ?: return null
        val gen = prefs.getLong(HWM_GEN, -1L).takeIf { it > 0 } ?: return null
        return ReceiverState(
            appliedGen = gen, phoneInstall = install, mode = com.rokid.context.core.model.PlanMode.SETTINGS, planJson = null, configHash = null,
            trialStartElapsed = null, trialMaxMs = null, bootId = "", previous = null, terminal = mapOf(gen to "auto:corrupt"), effective = true,
        )
    }

    override fun commit(s: ReceiverState) {
        val ok = prefs.edit().putString(KEY, ReceiverStateJson.encode(s)).putString(HWM_INSTALL, s.phoneInstall).putLong(HWM_GEN, s.appliedGen).commit()
        if (!ok) throw java.io.IOException("capture_plan not written")
    }

    companion object { const val PREFS = "capture_plan"; private const val KEY = "state"; private const val HWM_INSTALL = "hwm_install"; private const val HWM_GEN = "hwm_gen" }
}
