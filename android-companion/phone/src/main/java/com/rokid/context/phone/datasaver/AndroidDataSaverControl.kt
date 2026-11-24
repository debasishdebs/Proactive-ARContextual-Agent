package com.rokid.context.phone.datasaver

import android.content.Context
import com.rokid.context.ai.datasaver.DataSaverGraph
import com.rokid.context.core.net.DataMode
import com.rokid.context.phone.ui.settings.datasaver.DataSaverControl
import com.rokid.context.phone.ui.settings.datasaver.DataSaverUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Production control over the app's one [DataSaverGraph]. A mode change also re-ensures the night-job constraints (they follow the mode) while processing is on. Lives outside the settings tree: it reaches the app's graphs. */
class AndroidDataSaverControl(private val app: Context, private val graph: DataSaverGraph = DataSaverGraph.get(app)) : DataSaverControl {
    private val flow = MutableStateFlow(DataSaverUiState())
    override val state: StateFlow<DataSaverUiState> = flow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun read() = DataSaverUiState(graph.settingsNow(), graph.costNow(), graph.ledger.today(), graph.mobile.today(), graph.limitNow(), graph.limitReachedNow(), flow.value.waiting, graph.stuckNow(), com.rokid.context.core.net.NetworkCosts.roamingWhy(graph.snapshot(), graph.settingsNow().abroad, graph.settingsNow().wifiMeteredAbroad))

    override fun setMode(m: DataMode) {
        graph.settingsStore.setMode(m); flow.value = read() // the settings collector refreshes the gate off the main thread
        try { if (com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(app)) com.rokid.context.work.OrchestrationScheduler(app).ensureNightIndex() } catch (_: Exception) { /* the runtime gate still holds */ }
    }
    override fun setAbroad(on: Boolean) {
        graph.settingsStore.setAbroad(on); flow.value = read()
        // Model downloads follow the switch at once: ON = Wi-Fi only (a running one is stopped and resumes on Wi-Fi), OFF = the user's "Allow mobile data" again.
        scope.launch { try { com.rokid.context.data.models.LocalModelStore(app).reevaluate() } catch (_: Exception) { } }
    }
    override fun setSmallOnRoaming(on: Boolean) { graph.settingsStore.setSmallOnRoaming(on); flow.value = read() }
    override fun setDailyLimitOn(on: Boolean) { graph.settingsStore.setDailyLimitOn(on); flow.value = read() }
    override fun setLimitMb(mb: Int) { graph.settingsStore.setLimitMb(mb); flow.value = read() }
    override fun setLimitPhotos(n: Int) { graph.settingsStore.setLimitPhotos(n); flow.value = read() }
    override fun setWifiMeteredAbroad(on: Boolean) { graph.settingsStore.setWifiMeteredAbroad(on); flow.value = read() }
    init { flow.value = read() }

    override fun refresh() {
        graph.refresh() // re-reads Android Data Saver and the country facts too
        flow.value = read()
        scope.launch { // the waiting count is a database read: off the main thread
            val dao = try { com.rokid.context.memory.MemoryGraph.get(app).db.frameStateDao() } catch (e: Exception) { return@launch }
            val n = try { dao.dataSaverParkedCount() } catch (e: Exception) { return@launch }
            val bytes = if (n > 0 && graph.heldBack()) try { dao.dataSaverParkedPaths().sumOf { java.io.File(it).let { f -> if (f.isFile) f.length() else 0L } } } catch (e: Exception) { 0L } else 0L
            // after Wi-Fi returns the parked frames are no longer held back, they are uploading: show only frames still blocked
            flow.value = flow.value.copy(waiting = if (graph.heldBack()) n else 0, waitingBytes = bytes)
        }
    }

    /** Binder calls and a database read every 2 s: never on the main thread. */
    override suspend fun poll() { while (true) { withContext(Dispatchers.IO) { refresh() }; delay(2_000) } }
}
