package com.rokid.context.phone.ui.activity

import android.content.Context
import com.rokid.context.phone.hub.HubSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.Collator
import java.util.Locale

/** The Activity tab's sort menu. Alphabetical orders compare the row's title / memory text. */
enum class ActivitySort(val label: String) {
    LATEST_FIRST("Latest first"),
    OLDEST_FIRST("Oldest first"),
    A_Z("A\u2013Z"),
    Z_A("Z\u2013A");

    val alphabetical: Boolean get() = this == A_Z || this == Z_A

    /** Case-insensitive, locale-aware title order for [A_Z] / [Z_A] (null for the time orders). */
    fun <T> titleOrder(title: (T) -> String): Comparator<T>? {
        if (!alphabetical) return null
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val az = Comparator<T> { a, b -> collator.compare(title(a), title(b)) }
        return if (this == A_Z) az else az.reversed()
    }

    companion object {
        val DEFAULT = LATEST_FIRST
        fun parse(raw: String?): ActivitySort = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** Where the chosen order lives; process-wide (PhoneDeps), so a recreated screen reads the same value. */
interface ActivitySortStore {
    val order: StateFlow<ActivitySort>
    fun set(order: ActivitySort)
}

/** Tests and previews: not persisted. */
class InMemoryActivitySort(initial: ActivitySort = ActivitySort.DEFAULT) : ActivitySortStore {
    private val state = MutableStateFlow(initial)
    override val order: StateFlow<ActivitySort> = state
    override fun set(order: ActivitySort) { state.value = order }
}

/**
 * A plain UI preference, kept under its own key in the existing `hub_settings` prefs file, which BackupRulesTest
 * rules restorable (it holds no DB ids and nothing about the set-up).
 */
class PrefsActivitySort(context: Context) : ActivitySortStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(ActivitySort.parse(prefs.getString(KEY, null)))
    override val order: StateFlow<ActivitySort> = state

    override fun set(order: ActivitySort) {
        state.value = order
        prefs.edit().putString(KEY, order.name).apply()
    }

    companion object {
        const val PREFS = HubSettingsStore.PREFS
        const val KEY = "activity_sort"
    }
}
