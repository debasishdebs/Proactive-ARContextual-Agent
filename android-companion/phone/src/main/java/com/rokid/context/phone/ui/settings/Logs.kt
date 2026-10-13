package com.rokid.context.phone.ui.settings

import android.os.Process
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.CodeBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Diagnostics › Logs (spec §6.3 "full detail → Diagnostics"): this app's own recent log lines. */
object LogReader {
    private val SECRET = Regex("(?i)(api[-_ ]?key|authorization|bearer\\s)")

    /** Our logs never carry keys (SafeLog); lines that even look like they do are dropped anyway. */
    fun redact(lines: List<String>): List<String> = lines.filterNot { SECRET.containsMatchIn(it) }

    suspend fun ownLines(max: Int = 400): List<String> = withContext(Dispatchers.IO) {
        val p = ProcessBuilder("logcat", "-d", "-t", max.toString(), "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        try { redact(p.inputStream.bufferedReader().readLines()) } finally { p.destroy() }
    }
}

/** Diagnostics › Export diagnostics: recent job lines + own log lines, both through [LogReader.redact]. */
object DiagnosticsExport {
    /** Not `proactive-context-memories-*.json`, so a memory export's clean-up leaves it alone (F22). */
    const val FILE_NAME = "proactive-context-diagnostics.txt"

    /** Writes into `cacheDir/exports`, the only folder the FileProvider shares. */
    suspend fun write(cacheDir: java.io.File, traces: List<String>, logs: List<String>): java.io.File = withContext(Dispatchers.IO) {
        val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
        java.io.File(dir, FILE_NAME).apply {
            writeText("Recent jobs\n" + LogReader.redact(traces).joinToString("\n") + "\n\nLogs\n" + LogReader.redact(logs).joinToString("\n") + "\n")
        }
    }
}

data class LogsUiState(val text: String = "", val loading: Boolean = true)

class LogsViewModel(private val read: suspend () -> List<String>) : ViewModel() {
    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val text = try { read().joinToString("\n").ifEmpty { "No log lines yet" } } catch (c: CancellationException) { throw c } catch (e: Exception) { "Couldn't read the logs" }
            _state.update { LogsUiState(text, loading = false) }
        }
    }
}

@Composable
fun LogsScreen(state: LogsUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    SettingsPage("Logs", onBack, tag = "logs", actions = { SecondaryAction("Refresh", onRefresh, tag = "logs_refresh", enabled = !state.loading) }) {
        item { SettingsCard { CodeBox(state.text, null, tag = "logs_text", minLines = 20) } }
    }
}
