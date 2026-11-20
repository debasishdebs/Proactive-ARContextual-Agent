package com.rokid.context.phone.ui.settings.modelprivacy

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.ai.resolve.ModelPrivacyStore
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ResolveReason
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.work.frames.place.PlaceResolvers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the Model privacy screen shows. [placeUnavailable] is the typed reason Place Guide has no model to confirm with right now (null = it has one, or it is not asked). */
data class ModelPrivacyUiState(val mode: ModelPrivacy = ModelPrivacy.DEFAULT, val placeUnavailable: ResolveReason? = null, /** The stored choice was lost (the file was emptied or corrupted): the mode reads Offline only until the user picks one. */ val wasReset: Boolean = false, /** The first load of the stored mode has not finished: show "Loading", never a guess. */ val loading: Boolean = false, /** A change is being written to the disk. */ val saving: Boolean = false, /** The last change could not be written to the disk: it holds for this run only. */ val saveFailed: Boolean = false)

/** All the screen's words and rules, pure so a JVM test pins them. Nothing here names an endpoint, host or place. */
object ModelPrivacyModel {
    const val TITLE = "Model privacy"
    const val ROW_TITLE = "Model privacy"
    const val MODE_TITLE = "Where models may run"

    /** Option labels, in [ModelPrivacy] order. */
    val MODE_LABELS = listOf("Automatic (default)", "Offline only")

    fun modeNote(mode: ModelPrivacy): String = when (mode) {
        ModelPrivacy.AUTO -> "Uses your cloud or home-network models whenever there is a connection."
        ModelPrivacy.OFFLINE_ONLY -> "Nothing goes to a remote model: no photos, place names or questions. Only models on this phone or on your home network are used."
    }

    /** The row subtitle in Settings. */
    fun rowSubtitle(mode: ModelPrivacy): String = when (mode) {
        ModelPrivacy.AUTO -> "Automatic"
        ModelPrivacy.OFFLINE_ONLY -> "Offline only"
    }

    const val OFFLINE_EFFECT = "Photo descriptions and place matches that need a remote model wait. Place cards show nearby names only, and no place is fetched from the internet (seeding an area is refused too), and the street map shows no new tiles."
    const val QUEUED_NOTE = "Questions and photos that are waiting are sent after you switch back to Automatic (or add an on-device model)."
    const val LOADING = "Loading…"
    const val SAVE_FAILED = "Couldn't save this choice; it may not survive a restart."
    const val SAVING = "Saving…"
    const val RESET_NOTICE = "Model privacy settings were reset: check them. Offline only is used until you choose."
    const val PLACE_NEEDS_MODEL = "Place Guide: needs an on-device model. None is available yet, so matching is paused."
    const val DOWNLOAD_NOTE = "Downloading an on-device model still uses the internet once; nothing of yours is sent."

    /** One line about Place Guide under Offline only; null when there is nothing to say. */
    fun placeLine(s: ModelPrivacyUiState): String? =
        if (s.mode == ModelPrivacy.OFFLINE_ONLY && s.placeUnavailable == ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL) PLACE_NEEDS_MODEL else null
}

/** What the screen needs (production: [AndroidModelPrivacyControl]; tests: a fake). */
interface ModelPrivacyControl {
    val state: StateFlow<ModelPrivacyUiState>
    fun setMode(m: ModelPrivacy)
    /** Re-reads the Place Guide status (a database read: off the main thread). */
    fun refresh()
}

class AndroidModelPrivacyControl(
    private val app: Context, private val store: ModelPrivacyStore = ModelPrivacyStore.get(app),
    /** The Place Guide decision for the status line (null = nothing to say). Tests pass a stub: the production one reads the bindings. */
    private val placeStatus: suspend () -> com.rokid.context.core.net.BackendPick? = { PlaceResolvers.status(app) },
) : ModelPrivacyControl {
    private val flow = MutableStateFlow(ModelPrivacyUiState(mode = store.peek() ?: ModelPrivacy.DEFAULT, wasReset = false, loading = store.peek() == null))
    override val state: StateFlow<ModelPrivacyUiState> = flow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cancels the control's own scope: the route calls it when the screen leaves. */
    fun close() { scope.cancel() }

    override fun setMode(m: ModelPrivacy) {
        // The mode is in force the moment this returns (begin is synchronous): the router and the network guards read it at once, even if the screen is left right now. The disk write runs in the store's own scope.
        val pending = store.begin(m)
        flow.update { it.copy(mode = m, wasReset = false, loading = false, saving = true, saveFailed = false) }
        store.commitAsync(pending) { ok -> flow.update { it.copy(saving = false, saveFailed = !ok) } }
    }

    override fun refresh() {
        scope.launch { // the first load of the prefs and the resolver's binding read stay off the main thread
            store.warm()
            // read at update time, so a tap made while this was loading is never overwritten by an older value
            flow.update { it.copy(mode = store.current(), wasReset = store.wasReset.value, loading = false) }
            val pick = try { placeStatus() } catch (e: Exception) { return@launch }
            flow.update { it.copy(placeUnavailable = if (pick != null && pick.backend == null) pick.reason else null) }
        }
    }

    init { refresh() }
}

@Composable
fun ModelPrivacyRoute(control: ModelPrivacyControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { control.refresh() }
    androidx.compose.runtime.DisposableEffect(control) { onDispose { (control as? AndroidModelPrivacyControl)?.close() } }
    ModelPrivacyScreen(state, back, control::setMode)
}

@Composable
fun ModelPrivacyScreen(state: ModelPrivacyUiState, back: () -> Unit, onMode: (ModelPrivacy) -> Unit) {
    SettingsPage(ModelPrivacyModel.TITLE, back, tag = "model_privacy_settings") {
        item {
            SettingsCard {
                if (state.loading) { NoticeText(ModelPrivacyModel.LOADING, tag = "model_privacy_loading"); return@SettingsCard }
                if (state.saving) NoticeText(ModelPrivacyModel.SAVING, tag = "model_privacy_saving")
                if (state.saveFailed) NoticeText(ModelPrivacyModel.SAVE_FAILED, error = true, tag = "model_privacy_save_failed")
                ChoiceRow(ModelPrivacyModel.MODE_TITLE, ModelPrivacyModel.MODE_LABELS, state.mode.ordinal, { onMode(ModelPrivacy.entries[it]) }, tag = "model_privacy_mode")
                NoticeText(ModelPrivacyModel.modeNote(state.mode), tag = "model_privacy_mode_note")
                if (state.wasReset) NoticeText(ModelPrivacyModel.RESET_NOTICE, error = true, tag = "model_privacy_reset")
                if (state.mode == ModelPrivacy.OFFLINE_ONLY) {
                    NoticeText(ModelPrivacyModel.OFFLINE_EFFECT, tag = "model_privacy_effect")
                    NoticeText(ModelPrivacyModel.QUEUED_NOTE, tag = "model_privacy_queued")
                    ModelPrivacyModel.placeLine(state)?.let { NoticeText(it, tag = "model_privacy_place") }
                    NoticeText(ModelPrivacyModel.DOWNLOAD_NOTE, tag = "model_privacy_download")
                }
            }
        }
    }
}
