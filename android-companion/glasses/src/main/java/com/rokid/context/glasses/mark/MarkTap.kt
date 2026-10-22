package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The wearer's feedback for a mark, local and never waiting for the phone: a corner "✓" for 600 ms, or "mark undone" on undo. */
class MarkCue(private val scope: CoroutineScope, private val durationMs: Long = DURATION_MS) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text
    private var generation = 0L

    fun show(text: String) {
        val g = synchronized(this) { _text.value = text; ++generation }
        scope.launch { delay(durationMs); synchronized(this@MarkCue) { if (generation == g) _text.value = null } }
    }

    companion object { const val DURATION_MS = 600L; const val MARKED = "✓"; const val UNDONE = "mark undone" }
}

/**
 * Slice 3a Task 30 (design §4.3): a D-pad tap while our app is in the foreground takes a mark, then the normal push-to-talk goes on.
 * The mark is queued (persisted) first, sent if the link is up, and the cue shows at once. **A mark never touches capture** (S45): this
 * class has no capture dependency. A double-tap while that listen is recording undoes its mark ([onUndo]); once the listen is finished
 * or cancelled the mark is final.
 */
class MarkTap(
    private val outbox: MarkOutbox,
    private val cue: MarkCue,
    private val send: (MarkMessage) -> Boolean,
    private val enabled: () -> Boolean,
    private val wallClock: () -> Long,
    private val elapsed: () -> Long,
    private val bootCount: () -> Int,
) {
    @Volatile private var current: Long? = null

    /** Takes the mark now; [listen] = a push-to-talk is about to start. Returns its seq, or null (setting off). Never throws. */
    fun onTap(listen: Boolean): Long? {
        if (!enabled()) return null
        val m = try { outbox.record(wallClock(), elapsed(), bootCount(), listen) } catch (e: Exception) { return null }
        current = m.seq
        cue.show(MarkCue.MARKED)
        trySend(m)
        return m.seq
    }

    /** Double-tap while recording: undo this listen's mark (once). */
    fun onUndo() {
        val seq = current ?: return
        current = null
        val m = try { outbox.record(wallClock(), elapsed(), bootCount(), listen = false, undo = seq) } catch (e: Exception) { return }
        cue.show(MarkCue.UNDONE)
        trySend(m)
    }

    /** The listen ended (sent, auto-ended or cancelled): its mark is final. */
    fun onListenFinished() { current = null }

    /** Session open or hello: re-send what the phone has not acked (at most one burst), whatever its age up to 24 h. */
    fun flush() { for (m in outbox.pending()) if (!trySend(m)) break }

    private fun trySend(m: MarkMessage): Boolean = try { send(m) } catch (e: Exception) { false }
}
