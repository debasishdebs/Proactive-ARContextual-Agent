package com.rokid.context.phone.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.RecallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Find a photo". Searches only on [submit] (R8), never per keystroke and never on a database change; a change ([changes]: Room invalidation of
 * frame, frame_meta, memory and frame_source, S11) or [refresh] only re-reads the state of the photos already shown ([reenrich]), dropping forgotten ones.
 * Every submit bumps a generation, so a slower, older search cannot overwrite a newer one. No query, caption or history is logged or kept.
 */
class PhotoSearchViewModel(
    initialQuery: String?,
    private val search: suspend (String) -> RecallResult,
    private val reenrich: suspend (RecallResult) -> RecallResult,
    private val describePhotos: suspend (List<String>) -> DescribeResult,
    private val describeScene: suspend (String) -> DescribeResult,
    private val framePaths: suspend (List<String>) -> Map<String, String>,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
    changes: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(PhotoSearchUiState(query = initialQuery.orEmpty()))
    val state: StateFlow<PhotoSearchUiState> = _state.asStateFlow()

    private var generation = 0
    private var last: RecallResult? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { changes.collectLatest { refresh() } }
        if (!initialQuery.isNullOrBlank()) submit()
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun submit() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        val g = ++generation
        refreshJob?.cancel()
        last = null // a refresh that starts while this search runs has nothing of the old query to write back (I3)
        _state.update { it.copy(searching = true, status = null) }
        viewModelScope.launch {
            val r = try { search(q) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (g != generation) return@launch // an older search finished late
            val tiles = r?.let { build(it) }
            if (g != generation) return@launch
            last = r
            _state.update { s ->
                if (r == null || tiles == null) s.copy(searching = false, tiles = emptyList(), banner = null, note = PhotoSearchModel.SEARCH_FAILED)
                else s.copy(searching = false, tiles = tiles, banner = PhotoSearchModel.banner(r), note = PhotoSearchModel.note(r))
            }
        }
    }

    /** Re-reads the shown photos (database change, return to the screen, after a describe). A new search in between wins. */
    fun refresh() {
        val r = last ?: return
        val g = generation
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val fresh = try { reenrich(r) } catch (c: CancellationException) { throw c } catch (_: Exception) { return@launch }
            val tiles = build(fresh)
            if (g != generation || last !== r) return@launch // a newer search began, or finished, since this refresh started
            last = fresh
            _state.update { it.copy(tiles = tiles, note = PhotoSearchModel.note(fresh)) }
        }
    }

    fun describe(frameId: String) = runDescribe { describePhotos(listOf(frameId)) }

    fun describeScene(clusterId: String) = runDescribe { describeScene.invoke(clusterId) }

    private fun runDescribe(call: suspend () -> DescribeResult) {
        if (_state.value.describing) return
        _state.update { it.copy(describing = true, status = null) }
        viewModelScope.launch {
            val r = try { call() } catch (c: CancellationException) { throw c } catch (_: Exception) { DescribeResult.Unavailable }
            _state.update { it.copy(describing = false, status = PhotoSearchModel.status(r)) }
            refresh() // the new memories turn the check on
        }
    }

    private suspend fun build(r: RecallResult): List<PhotoTile> {
        val stored = (r.image + r.captions).filter { it.photoStored }.map { it.frameId }.distinct()
        val paths = if (stored.isEmpty()) emptyMap() else try { framePaths(stored) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyMap() }
        return PhotoSearchModel.tiles(r, paths, zone(), today())
    }
}
