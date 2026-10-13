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
    /**
     * Credential shapes (fix round 1, m4): key/auth headers, Azure's subscription key, key/token/sig query params,
     * token/secret/password values, bare `sk-…` keys and JWTs. `--pid` also carries SDK lines. Long base64 runs are
     * checked apart ([BASE64_RUN], [isLikelyBase64Secret]) so JNI class descriptors are kept (P8 D3).
     */
    private val SECRET = Regex("(?i)(api[-_. ]?key|authorization|bearer\\s|subscription-key|[?&](key|token|sig)=|access[_-]?token|(token|secret|password)\\s*[=:])|\\bsk-[A-Za-z0-9_-]{8,}|eyJ[A-Za-z0-9_-]{10,}\\.")
    private val BASE64_RUN = Regex("[A-Za-z0-9+/]{40,}")
    private val DIGIT = Regex("[0-9]")
    private val UPPER = Regex("[A-Z]")
    private val LOWER = Regex("[a-z]")
    /** A JNI/Java path segment: an identifier whose digits, if any, come last (`v2`, `Interface2`), as random Base64 rarely is. */
    private val PATH_SEGMENT = Regex("[A-Za-z_$][A-Za-z_$]*[0-9]*")

    /**
     * A 40+ char base64 run is a likely key when it mixes a digit, an upper- and a lower-case letter and is not a
     * `/`-separated identifier path such as a JNI descriptor (`Lcom/rokid/cxr/.../SomeCallbackInterface;`).
     */
    internal fun isLikelyBase64Secret(run: String): Boolean {
        if (!DIGIT.containsMatchIn(run) || !UPPER.containsMatchIn(run) || !LOWER.containsMatchIn(run)) return false
        val identifierPath = '/' in run && run.split('/').all { PATH_SEGMENT.matches(it) }
        return !identifierPath
    }

    private fun looksSecret(line: String): Boolean =
        SECRET.containsMatchIn(line) || BASE64_RUN.findAll(line).any { isLikelyBase64Secret(it.value) }

    /** Our logs never carry keys (SafeLog); lines that even look like they do are dropped anyway. */
    fun redact(lines: List<String>): List<String> = lines.filterNot(::looksSecret)

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
