package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.rokid.context.canvas.moment.MomentLocationGraph
import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.LocatedMoment
import com.rokid.context.memory.db.SceneLocatedMoment
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.list.ListItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The read-only source of the map view; production reads through [MomentLocationGraph] (the one allow-listed reader besides the recorder), so a Forget shows on the next call. */
interface MomentMapSource {
    suspend fun moments(range: LongRange): List<LocatedMoment>
    suspend fun scenes(range: LongRange): List<SceneLocatedMoment>
    suspend fun members(momentIds: List<String>): List<SceneMember>
}

class AppMomentMapSource(private val app: Context) : MomentMapSource {
    override suspend fun moments(range: LongRange) = MomentLocationGraph.located(app, range)
    override suspend fun scenes(range: LongRange) = MomentLocationGraph.sceneLocated(app, range)
    override suspend fun members(momentIds: List<String>) = MomentLocationGraph.members(app, momentIds)
}

/** What the map view holds: the weights of the current period and level (never persisted), whether the read finished, and whether it hit the row cap. */
data class ActivityMapState(val points: List<HeatPoint> = emptyList(), val loaded: Boolean = false, val capped: Boolean = false)

/** One Moment row of a hot cell and the preview ref its tap opens (null: nothing to open). */
data class MapMomentRow(val item: ListItem, val ref: String?)

/**
 * Loads the map view's weights through the read-only [MomentMapSource] (every call asks the database again, so "Delete saved locations" and a Forget show on the next refresh) and the rows of a tapped cell.
 * Nothing is cached across calls, logged or sent. [load] runs only while the map is on screen (the screen calls it), so the list mode reads nothing.
 */
class ActivityMapViewModel(private val repo: MomentMapSource, private val timeline: TimelineRepository, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(ActivityMapState())
    val state: StateFlow<ActivityMapState> = _state
    private var job: Job? = null

    fun load(range: LongRange?, g: Granularity) {
        job?.cancel()
        job = viewModelScope.launch(work) {
            val r = range ?: 0L..Long.MAX_VALUE - 1
            val moments = if (g == Granularity.SCENES) emptyList() else repo.moments(r)
            val scenes = if (g == Granularity.SCENES) repo.scenes(r) else emptyList()
            val pts = MomentHeat.points(g, moments, scenes, range)
            _state.value = ActivityMapState(pts, loaded = true, capped = (moments.size.coerceAtLeast(scenes.size)) >= MomentLocationGraph.MAP_MAX_ROWS)
        }
    }

    /** The rows of the Moments of one cell, in the order given. A Moment that is gone is absent. */
    suspend fun rows(momentIds: List<String>, clock: (Long) -> String): List<MapMomentRow> {
        val members = repo.members(momentIds).associateBy { it.momentId }
        val ordered = momentIds.mapNotNull(members::get)
        val memories = timeline.memoriesByIds(ordered.flatMap { it.memoryIds }).filter { it.reviewState == ReviewState.OK }.associateBy { it.id }
        return ordered.map { m ->
            val mem = m.memoryIds.firstNotNullOfOrNull(memories::get)
            MomentHeat.row(m, mem?.text, mem?.id, clock).let { (item, ref) -> MapMomentRow(item, ref) }
        }
    }

    fun clear() { job?.cancel(); _state.update { ActivityMapState() } }
    override fun onCleared() { job?.cancel() }
}
