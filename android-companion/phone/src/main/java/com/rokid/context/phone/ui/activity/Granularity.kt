package com.rokid.context.phone.ui.activity

import android.content.Context
import com.rokid.context.phone.hub.HubSettingsStore
import com.rokid.context.phone.ui.list.ListKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The level the Activity page browses: Photos (frames), Moments or Scenes. [MOMENTS] is the default and is the page exactly as it always was; another level changes which list "See all" opens and adds a summary card
 * for that level. Pure: the mapping from a level to its list and its words is here, so a test can pin it.
 */
enum class Granularity(val label: String, val list: ListKind, val noun: String) {
    PHOTOS("Photos", ListKind.FRAMES, "photos"),
    MOMENTS("Moments", ListKind.MOMENTS, "moments"),
    SCENES("Scenes", ListKind.SCENES, "scenes");

    val isDefault: Boolean get() = this == DEFAULT
    val seeAll: String get() = "See all $noun"

    /** How many items of this level the period holds (the summary card's number). Moments are the memories tile, so the default page shows no extra card. */
    fun count(t: Tiles): Int = when (this) { PHOTOS -> t.frames; MOMENTS -> t.memories; SCENES -> t.scenes }

    companion object {
        val DEFAULT = MOMENTS
        /** Anything unknown or missing is the default (a damaged or newer preference never breaks the page). */
        fun parse(raw: String?): Granularity = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** Where the chosen level lives; process-wide (PhoneDeps), like [ActivitySortStore]. */
interface GranularityStore {
    val level: StateFlow<Granularity>
    fun set(level: Granularity)
}

/** Tests and previews: not persisted. */
class InMemoryGranularity(initial: Granularity = Granularity.DEFAULT) : GranularityStore {
    private val state = MutableStateFlow(initial)
    override val level: StateFlow<Granularity> = state
    override fun set(level: Granularity) { state.value = level }
}

/** A plain UI preference under its own key in the restorable `hub_settings` file (see [PrefsActivitySort]); a read or write that fails keeps the default. */
class PrefsGranularity(context: Context) : GranularityStore {
    private val prefs = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
    private val state = MutableStateFlow(Granularity.parse(runCatching { prefs?.getString(KEY, null) }.getOrNull()))
    override val level: StateFlow<Granularity> = state

    override fun set(level: Granularity) {
        state.value = level
        runCatching { prefs?.edit()?.putString(KEY, level.name)?.apply() }
    }

    companion object {
        const val PREFS = HubSettingsStore.PREFS
        const val KEY = "activity_granularity"
    }
}
