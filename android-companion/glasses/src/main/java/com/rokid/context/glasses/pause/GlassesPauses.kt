package com.rokid.context.glasses.pause

import android.util.Log
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface PauseToggle {
    data class Paused(val record: PauseRecord) : PauseToggle
    data class Resumed(val clears: Set<String>) : PauseToggle
}

/**
 * Glasses-local pause enforcement (spec §1.7 exception, §5.5). A double-tap pause applies at once and persists, linked
 * or not; phone records merge by union and are never removed here; capture runs only when no record is active on the
 * glasses clock; before the store is loaded nothing is allowed. A Resume names only the records the glasses know, and is
 * repeated in each `pc_hello` until the phone stops listing the ids.
 */
class GlassesPauses(
    private val store: PauseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<PauseRecord>>(emptyList())
    val records: StateFlow<List<PauseRecord>> = _records
    /**
     * Ids the wearer resumed locally (-> glasses-clock resume instant); a phone push that still lists one must not
     * re-pause capture. Each stays until a phone push no longer lists it (the phone has applied the Resume), and is
     * re-sent in every `pc_hello` until then, so a Resume made while the link was down is not lost.
     */
    @Volatile private var tombstones: Map<String, Long> = emptyMap()
    @Volatile private var loaded = false

    suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

    /** Resumes the phone has not confirmed yet (pauseId -> glasses-clock instant), for `Hello.resumedAtMs`. */
    fun resumedAtMs(): Map<String, Long> = tombstones

    fun active(nowMs: Long = clock()): List<PauseRecord> = _records.value.filter { it.isActiveAt(nowMs) }

    fun captureAllowed(nowMs: Long = clock()): Boolean = loaded && active(nowMs).isEmpty()

    suspend fun toggle(): PauseToggle = mutex.withLock {
        loadLocked()
        val now = clock()
        val active = active(now)
        if (active.isNotEmpty()) {
            val clears = active.map { it.pauseId }.toSet()
            tombstones = tombstones + clears.associateWith { now }
            save(_records.value.filter { it.pauseId !in clears && it.isActiveAt(now) })
            PauseToggle.Resumed(clears)
        } else {
            val r = PauseRecord(newId(), PauseOrigin.GLASSES, createdAtMs = now, untilMs = null)
            save(_records.value.filter { it.isActiveAt(now) } + r)
            PauseToggle.Paused(r)
        }
    }

    /** Union with the phone's merged set (most restrictive wins); expired or known records change nothing. */
    suspend fun mergeFromPhone(records: List<PauseRecord>) = mutex.withLock {
        loadLocked()
        val known = _records.value.map { it.pauseId }.toSet()
        val now = clock()
        val listed = records.map { it.pauseId }.toSet()
        // A tombstone ends only once the phone no longer lists the id. No time limit: after a long offline spell the
        // first push still lists it (the hello naming it is handled after), and must not re-pause capture.
        val liveTombs = tombstones.filterKeys { it in listed }
        val added = records.filter { it.pauseId !in known && it.pauseId !in liveTombs && it.isActiveAt(now) }
        val tombsChanged = liveTombs != tombstones
        tombstones = liveTombs
        if (added.isNotEmpty() || tombsChanged) save(_records.value + added)
    }

    private suspend fun loadLocked() {
        if (loaded) return
        _records.value = try {
            store.read().also { tombstones = it.tombstones }.records
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store unreadable (${e.javaClass.simpleName}): staying paused")
            listOf(PauseRecord(PauseStore.UNREADABLE_ID, PauseOrigin.GLASSES, clock(), null))
        }
        loaded = true
    }

    /** Memory first, so enforcement holds even if the disk write fails. */
    private suspend fun save(list: List<PauseRecord>) {
        _records.value = list
        try {
            store.write(PauseState(list, tombstones))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store write failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val TAG = "PcPause"
    }
}
