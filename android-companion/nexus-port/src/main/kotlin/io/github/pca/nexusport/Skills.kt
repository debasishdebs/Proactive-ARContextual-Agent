package io.github.pca.nexusport

/** The four action-only Nexus skills (T6). No operation returns memory content: the only output is a capture-state enum. */
enum class SkillOp(val id: String) {
    STATUS("status"), PAUSE_CAPTURE("pause_capture"), RESUME_CAPTURE("resume_capture"), MARK_MOMENT("mark_moment");

    /** A read has no effect; the other three are actions. */
    val isAction: Boolean get() = this != STATUS

    companion object {
        fun of(id: String): SkillOp? = entries.firstOrNull { it.id == id }
    }
}

/** The values of the one output field `state` (an enum in the catalog). Never a place name. */
object SkillState {
    const val CAPTURING = "capturing"
    const val PAUSED = "paused"
    const val ZONE_PAUSED = "zone_paused"
    const val STOPPED = "stopped"
    val ALL = setOf(CAPTURING, PAUSED, ZONE_PAUSED, STOPPED)
}

/** The domain failure codes of the skills. Fixed tokens: they are what logs carry and what the Assistant sees. All match Nexus's `[a-z][a-z0-9_]{1,47}`. */
object SkillCodes {
    const val PAUSE_ZONE = "pause_zone"
    const val PAUSED = "paused"
    const val NOT_WORN = "not_worn"
    const val DISABLED = "disabled"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
    const val UNSUPPORTED_OPERATION = "unsupported_operation"
    const val DEADLINE_EXCEEDED = "deadline_exceeded"
    const val INVALID_RESULT = "invalid_result"
    const val BUSY = "busy"
    val ALL = setOf(PAUSE_ZONE, PAUSED, NOT_WORN, DISABLED, UNAVAILABLE, FAILED, UNSUPPORTED_OPERATION, DEADLINE_EXCEEDED, INVALID_RESULT, BUSY)

    /** Anything that is not a known token becomes [FAILED]: a provider cannot smuggle text through a code. */
    fun sanitize(code: String?): String = if (code != null && code in ALL) code else FAILED
}

sealed interface SkillResult {
    data class Done(val state: String) : SkillResult
    data class Refused(val code: String) : SkillResult
}

/**
 * The phone app's implementation of the four skills, over PCA's own paths: status from the capture state, pause/resume through the pause controller, `mark_moment` through the SAME mark path as the HUD Mark
 * action (so its zone and pause refusals apply). It must refuse with [SkillCodes.PAUSE_ZONE] / [SkillCodes.PAUSED] by itself; the sink checks the fences too (defence in depth). Returns the capture
 * state AFTER the action. Must answer well inside 12 s. Returns no memory content.
 */
interface NexusSkillProvider {
    suspend fun invoke(op: SkillOp): SkillResult
}

/** The wire form of [NexusSkillHost.invoke]: `ok:<state>` or `fail:<code>`. */
object SkillWire {
    fun encode(r: SkillResult): String = when (r) {
        is SkillResult.Done -> if (r.state in SkillState.ALL) "ok:${r.state}" else "fail:${SkillCodes.INVALID_RESULT}"
        is SkillResult.Refused -> "fail:${SkillCodes.sanitize(r.code)}"
    }

    /** Strict: anything that is not exactly `ok:<known state>` or `fail:<known code>` is [SkillCodes.INVALID_RESULT]. */
    fun decode(s: String?): SkillResult = when {
        s == null -> SkillResult.Refused(SkillCodes.INVALID_RESULT)
        s.startsWith("ok:") && s.substring(3) in SkillState.ALL -> SkillResult.Done(s.substring(3))
        s.startsWith("fail:") && s.substring(5) in SkillCodes.ALL -> SkillResult.Refused(s.substring(5))
        else -> SkillResult.Refused(SkillCodes.INVALID_RESULT)
    }
}
