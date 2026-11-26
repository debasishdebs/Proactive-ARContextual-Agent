package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a "Centre on me" tap has produced so far. Held on screen only: never stored, logged or sent. */
data class CentreUi(val finding: Boolean = false, val note: String? = null, val request: CentreRequest? = null)

object CentreWords {
    const val FINDING = "Finding you..."
    const val ALLOW = "Allow location to centre the map on you."
}

/**
 * The one-shot "Centre on me" (offline-maps design S2), shared by the map screens. A tap: a FRESH fix the app already has is used at once (no new fix); otherwise the location permission is asked
 * (only now, on the tap) or ONE fix is requested ([fix]: GPS up to 20 s when there is no network, which needs no network) while "Finding you..." and a Cancel are shown. The result goes to [state]
 * only. It never touches pause-zone state: the zone guard, its fence and `BLOCKED_INSIDE` are not involved, the dot is only drawn.
 */
class CentreFlow(private val scope: CoroutineScope, private val fix: CentreFix?, private val granted: () -> Boolean, private val ask: () -> Unit, private val fresh: () -> LatLon? = { null },
                 private val noFix: String = OnlineMapWords.NO_FIX) {
    private val _state = MutableStateFlow(CentreUi())
    val state: StateFlow<CentreUi> = _state
    private var job: Job? = null
    private var seq = 0

    fun tap() {
        if (_state.value.finding) return
        val known = fresh()
        if (known != null) { _state.update { CentreUi(request = CentreRequest(++seq, known)) }; return }
        if (!granted()) { _state.update { it.copy(note = CentreWords.ALLOW) }; ask(); return }
        val f = fix
        if (f == null) { _state.update { it.copy(note = noFix) }; return }
        _state.update { CentreUi(finding = true, request = it.request) }
        job = scope.launch {
            val p = try { f.current() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { if (p == null) CentreUi(note = noFix, request = it.request) else CentreUi(request = CentreRequest(++seq, p)) }
        }
    }

    /** Cancel on the "Finding you..." line: the fix request is cancelled (the platform request with it) and nothing is shown but the plain state. */
    fun cancel() {
        job?.cancel(); job = null
        _state.update { CentreUi(request = it.request) }
    }

    fun dismissNote() { _state.update { it.copy(note = null) } }
}
