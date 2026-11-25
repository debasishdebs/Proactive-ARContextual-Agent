package com.rokid.context.phone.ui.settings.mapzones

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate
import com.rokid.context.phone.ui.settings.privacy.zones.SeedMapControl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Map zones P2 (flag mapZonesP23): "Show cells" for one seed area and the production density control. Read only: nothing is fetched or written; no name or coordinate is logged.

data class CellCoverageState(val areaId: String? = null, val coverage: Coverage? = null, val message: String? = null)

/** Shows the per-cell status of ONE seed area at a time. A read that fails or an area too big to draw shows a message and no (wrong) colours. */
class CellCoverageViewModel(private val control: SeedMapControl, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(CellCoverageState())
    val state: StateFlow<CellCoverageState> = _state

    fun toggle(areaId: String, box: DoubleArray) {
        if (_state.value.areaId == areaId) { hide(); return }
        val gb = SeedEstimate.boxOf(box)
        if (gb == null) { _state.value = CellCoverageState(message = "This area cannot be shown cell by cell."); return }
        if (SeedEstimate.cells(gb) > SeedEstimate.MAX_CELLS) { _state.value = CellCoverageState(message = "This area is too large to show cell by cell."); return }
        _state.value = CellCoverageState(areaId = areaId)
        viewModelScope.launch(work) {
            val statuses = runCatching { control.cellStatuses(gb) }.getOrNull()
            val cov = statuses?.let { SeedCoverage.of(gb, it) }
            _state.update { if (it.areaId != areaId) it else if (cov == null) CellCoverageState(message = "Could not read the cells.") else it.copy(coverage = cov) }
        }
    }

    fun hide() { _state.value = CellCoverageState() }
    fun dismissMessage() = _state.update { it.copy(message = null) }
}

/** The production density control: the saved Moment locations through the app's read-only calls. The switch lives in the Developer prefs file (default OFF). */
class AppMomentDensity(private val app: Context) : MomentDensityControl {
    override fun isOn() = com.rokid.context.orchestration.forget.DeveloperFlags.mapMomentsDensity(app)
    override fun setOn(on: Boolean) = com.rokid.context.orchestration.forget.DeveloperFlags.setMapMomentsDensity(app, on)
    override suspend fun points(south: Double, west: Double, north: Double, east: Double, fromMs: Long, toMs: Long, limit: Int) =
        com.rokid.context.canvas.moment.MomentLocationGraph.pointsIn(app, south, west, north, east, fromMs, toMs, limit).map { DensityPoint(it.lat, it.lon, it.atMs) }
    override suspend fun range() = com.rokid.context.canvas.moment.MomentLocationGraph.timeRange(app)
}
