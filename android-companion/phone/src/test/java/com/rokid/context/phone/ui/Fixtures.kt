package com.rokid.context.phone.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.StateFrame
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

object Fixtures {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Monday 2026-10-12. */
    val TODAY: LocalDate = LocalDate.of(2026, 10, 12)
    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long = date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()
    /** 10:00 on [TODAY] in [ZONE]. */
    val NOW: Long = at(TODAY, 10)

    fun memory(
        id: String, text: String, at: Long, frameId: String? = null, confidence: Double = 0.8, tags: String = "",
        review: ReviewState = ReviewState.OK, trusted: Boolean = false,
    ) = MemoryEntity(id, frameId, text, "short_term", tags, MemoryTier.SHORT, confidence, review, trusted, at, false, searchText = "")

    fun frame(id: String, ts: Long, path: String = "/missing/$id.jpg") = FrameEntity(id = id, ts = ts, path = path, source = "rokid_glasses")

    fun ask(
        id: String, ts: Long, question: String = "Where are my keys?", answer: String = "On the hallway shelf.",
        sources: String = "[]", rating: Int? = null, redacted: Boolean = false, sourceStates: String? = null,
    ) = AskEntity(id, ts, question, answer, sources, rating, "phone_typed", redacted, sourceStates)

    fun nudge(id: String, ts: Long, text: String = "Milk looked almost empty", why: String = "Seen in the fridge at 09:41") =
        NudgeEntity(id, ts, text, why, "[]", 1, "h_$id", "phone", false)

    fun proposal(id: String, originId: String, type: String = "SHOPPING_ADD", state: String = "PENDING") =
        ProposalEntity(id, "ASK", originId, type, "{}", state, NOW + 60_000, null, null, NOW)

    fun stateFrame(
        capture: CaptureState = CaptureState.Running, nextAtMs: Long? = NOW + 252_000, battery: Int? = 82, ai: Health = Health.Ok,
    ) = StateFrame(seq = 1, capture = capture, nextCaptureAtMs = nextAtMs, intervalSec = 300, glassesBatteryPct = battery, phoneLinked = true, aiHealth = ai)

    /** A small non-JPEG file: exists on disk but does not decode (exercises the "Missing" path too). */
    fun jpg(ctx: Context, name: String): String =
        File(ctx.filesDir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath
}

object TestGraph {
    fun fresh(ctx: Context): MemoryGraph { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); return MemoryGraph.get(ctx) }
    fun close(ctx: Context) { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }
}

/** ViewModel scopes outlive a test; cancel them before closing the DB so no collector hits a closed database. */
/** Cancels and waits, so no child still touches Dispatchers.Main when MainDispatcherRule resets it. */
fun ViewModel.cancelScope() = runBlocking { viewModelScope.coroutineContext.job.cancelAndJoin() }
