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
    override fun load(): ReceiverState? = ReceiverStateJson.decode(prefs.getString(KEY, null))

    override fun commit(s: ReceiverState) {
        if (!prefs.edit().putString(KEY, ReceiverStateJson.encode(s)).commit()) throw java.io.IOException("capture_plan not written")
    }

    companion object { const val PREFS = "capture_plan"; private const val KEY = "state" }
}
