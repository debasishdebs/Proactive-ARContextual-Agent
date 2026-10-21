package com.rokid.context.phone.ui.settings.capture

import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.phone.context.ContextHubStats
import com.rokid.context.phone.context.ContextSourceSettings
import com.rokid.context.phone.context.SourceStatus
import com.rokid.context.phone.context.WatchMotionIntake
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest

/** The LiveFit signing certificate as lowercase hex SHA-256, or null when LiveFit is not installed (or has no readable certificate). */
fun interface LiveFitCertLookup { fun sha256(): String?; fun cannotPin(): Boolean = false }

/** The real lookup: the current signing certificate of `com.livear.fit` (spec §8). Never logged. */
class PackageLiveFitCertLookup(private val pm: PackageManager) : LiveFitCertLookup {
    override fun sha256(): String? = try {
        val info = pm.getPackageInfo(WatchMotionIntake.LIVEFIT_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info.signingInfo
        val sig = when {
            si == null -> null
            si.hasMultipleSigners() -> null
            else -> si.signingCertificateHistory?.lastOrNull() ?: si.apkContentsSigners.firstOrNull()
        }
        sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    } catch (e: Exception) { null }

    /** A multi-signer package can never match a single-certificate pin, so it cannot be pinned. */
    override fun cannotPin(): Boolean = try {
        pm.getPackageInfo(WatchMotionIntake.LIVEFIT_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.hasMultipleSigners() == true
    } catch (e: Exception) { false }
}

data class ContextSourcesUiState(
    val applyToCapture: Boolean = true,
    val watchOn: Boolean = false,
    val watchMissing: Boolean = false,
    val cannotPin: Boolean = false,
    val reToggleHint: Boolean = false,
    val stateLine: String = "",
) {
    companion object {
        fun word(s: ContextState): String = when (s) {
            ContextState.UNKNOWN -> "Unknown"
            ContextState.REST -> "Resting"
            ContextState.WALKING -> "Walking"
            ContextState.VEHICLE_PASSENGER -> "Passenger"
            ContextState.VEHICLE_DRIVING -> "Driving"
            ContextState.FLIGHT -> "Flying"
        }
    }
}

/**
 * Writes the opt-in settings only. The process-wide hub observes them through the settings listener and does the start, the stop
 * and the revocation of a published state; nothing here calls the hub.
 */
class ContextSourcesViewModel(
    private val settings: ContextSourceSettings,
    private val cert: LiveFitCertLookup,
    decision: StateFlow<ContextDecision>,
    status: StateFlow<Map<String, SourceStatus>>,
    private val stats: ContextHubStats? = null,
) : ViewModel() {
    private val missing = MutableStateFlow(false)
    private val multiSigner = MutableStateFlow(false)

    private fun of(d: ContextDecision, st: Map<String, SourceStatus>, miss: Boolean): ContextSourcesUiState {
        val snap = stats?.snapshot().orEmpty()
        val line = "Now: ${ContextSourcesUiState.word(d.state)} · watch ${st["watch_livefit"] ?: SourceStatus.OFF} · watch msgs ${snap["watch"] ?: 0} · rejected ${snap["watchRej"] ?: 0}"
        val on = settings.enabled(WATCH)
        val hint = on && (snap["watchRej"] ?: 0L) > 0L && (snap["watch"] ?: 0L) == 0L
        return ContextSourcesUiState(settings.applyToCapture, on, miss, multiSigner.value, hint, line)
    }

    val state: StateFlow<ContextSourcesUiState> = combine(decision, status, missing, settings.changes, multiSigner) { d, s, m, _, _ -> of(d, s, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(decision.value, status.value, missing.value))

    fun setApply(on: Boolean) { settings.applyToCapture = on }

    /** On: pin the certificate, then enable (the source refuses to start without a pinned digest). Off: disable, then clear the pin. */
    fun setWatch(on: Boolean) {
        if (on) {
            val sha = cert.sha256()
            if (sha == null) { multiSigner.value = cert.cannotPin(); missing.value = true; settings.setEnabled(WATCH, false); return }
            multiSigner.value = false
            missing.value = false
            settings.liveFitCertSha256 = sha
            settings.setEnabled(WATCH, true)
        } else {
            missing.value = false; multiSigner.value = false
            settings.setEnabled(WATCH, false)
            settings.liveFitCertSha256 = null
        }
    }

    private companion object { const val WATCH = "watch_livefit" }
}

@Composable
fun ContextSourcesScreen(state: ContextSourcesUiState, onBack: () -> Unit, onApply: (Boolean) -> Unit, onWatch: (Boolean) -> Unit) {
    SettingsPage("Phone, car, watch and photo context", onBack, tag = "context_sources") {
        item {
            SettingsCard {
                Text("Context", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Adjust capture to context", "Off: the state is only logged, capture is not changed.", state.applyToCapture, onApply, tag = "ctx_apply")
                SwitchRow("Galaxy Watch (LiveFit app)", null, state.watchOn, onWatch, tag = "ctx_watch_livefit")
                if (state.watchMissing) NoticeText(
                    if (state.cannotPin) "LiveFit has more than one signing certificate, so it cannot be pinned." else "Install LiveFit on this phone first.",
                    tag = "ctx_watch_missing",
                )
                if (state.reToggleHint) NoticeText("Messages are being rejected: if LiveFit was reinstalled or re-signed, turn this off and on again.", tag = "ctx_watch_hint")
                NoticeText(state.stateLine, tag = "ctx_state")
            }
        }
    }
}
