package com.rokid.context.work.frames.place.replay

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.work.frames.place.PlaceMatchResult

/** The `match` seam of the sampler. [note] is set by each call for the report ("truth_not_in_candidates", "hist_name_not_in_candidates", ...). */
interface ReplayMatcher {
    var note: String?
    suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult
}

interface MatchStage {
    val label: String
    fun create(bundle: ReplayBundle): ReplayMatcher
}

internal fun lineName(line: String) = line.substringAfter(". ").substringBeforeLast(" | ")
private fun result(choice: String, conf: Float) = PlaceMatchResult(PlaceMatchOutput(choice, conf), delivered = true)
private fun indexOfName(lines: List<String>, name: String) = lines.indexOfFirst { lineName(it).trim().equals(name.trim(), ignoreCase = true) }.let { if (it < 0) -1 else it + 1 }

abstract class BaseMatcher : ReplayMatcher { override var note: String? = null }

/** 1. The floor: no identification at all. */
object StubNone : MatchStage {
    override val label = "StubNone"
    override fun create(bundle: ReplayBundle): ReplayMatcher = object : BaseMatcher() { override suspend fun match(frameId: String, lines: List<String>) = result("NONE", 0.9f) }
}

/** 2. The trip's real model as far as it is known: the recorded "Looked at X" choice and confidence for those frames, NONE for every other. */
object Historical : MatchStage {
    override val label = "Historical"
    override fun create(bundle: ReplayBundle): ReplayMatcher {
        val byFrame = bundle.historical.associateBy { it.frameId }
        return object : BaseMatcher() {
            override suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult {
                val h = byFrame[frameId] ?: return result("NONE", 0.9f)
                val i = indexOfName(lines, h.name)
                if (i < 0) { note = "hist_name_not_in_candidates"; return result("0", h.conf) } // out of range: CONTRACT
                note = "hist_recorded"
                return result(i.toString(), h.conf)
            }
        }
    }
}

/** 3. A perfect identifier: inside a truth span with the target among the lines, that line at 0.9; otherwise NONE. */
object Oracle : MatchStage {
    override val label = "Oracle"
    override fun create(bundle: ReplayBundle): ReplayMatcher = object : BaseMatcher() {
        override suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult {
            val f = bundle.framesById[frameId] ?: return result("NONE", 0.9f)
            val name = bundle.spanAt(f.capturedAt)?.targetName ?: return result("NONE", 0.9f)
            val i = indexOfName(lines, name)
            if (i < 0) { note = "truth_not_in_candidates"; return result("NONE", 0.9f) }
            return result(i.toString(), 0.9f)
        }
    }
}

/** F5: matches the trip's own stored caption/object text of the frame against the candidate names. No new model call. Several distinct matches are ambiguous: NONE. */
object CaptionText : MatchStage {
    override val label = "CaptionText"
    private val STOP = setOf("museum", "gallery", "room", "fort", "show", "light", "sound", "kitchen", "bar")
    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    override fun create(bundle: ReplayBundle): ReplayMatcher = object : BaseMatcher() {
        override suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult {
            val text = bundle.framesById[frameId]?.text.orEmpty()
            if (text.isBlank()) { note = "caption_no_hit"; return result("NONE", 0.9f) }
            val tt = tokens(text); val compact = letters(text)
            val hit = lines.indices.filter { i ->
                val name = lineName(lines[i])
                val distinctive = tokens(name).filter { it.length >= 4 && it !in STOP }
                distinctive.any { it in tt } || (letters(name).length >= 6 && compact.contains(letters(name)))
            }
            if (hit.size != 1) { note = if (hit.size > 1) "caption_ambiguous" else "caption_no_hit"; return result("NONE", 0.9f) }
            return result((hit[0] + 1).toString(), 0.8f)
        }
    }
}

/**
 * 4. The real model: OFF and NOT implemented now (user ruling 2026-11-24: local only, no photo ever leaves the Mac). This is the hook for a later `Recorded` stage reading `real_match.tsv`.
 * It never reads a photo and the loader cannot: [ReplayBundle] opens only the TSV files.
 */
object RealModelStage {
    val enabled: Boolean = false
    fun create(): MatchStage = error("the real-model stage is not implemented: local only and OFF by default")
}
