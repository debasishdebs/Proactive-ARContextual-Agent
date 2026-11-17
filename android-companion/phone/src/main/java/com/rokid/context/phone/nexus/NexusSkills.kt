package com.rokid.context.phone.nexus

import com.rokid.context.voice.CaptureControl
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.UtteranceSource
import io.github.pca.nexusport.NexusGates
import io.github.pca.nexusport.NexusSkillProvider
import io.github.pca.nexusport.SkillCodes
import io.github.pca.nexusport.SkillOp
import io.github.pca.nexusport.SkillResult
import io.github.pca.nexusport.SkillState
import kotlinx.coroutines.CancellationException

/**
 * v2.0 T6: the four action-only skills over PCA's own paths. Status from the capture state; pause and resume through [capture] (the SAME entry the phone buttons and voice use, so a zone pause is never
 * cleared: `resume_capture` refuses with `pause_zone`, exactly like the glasses double-tap); `mark_moment` through [mark] (the existing phone-origin mark path) behind its zone, pause and worn refusals.
 * No operation returns memory content: the only output is the capture state. Logs: fixed tokens only.
 */
class PcaNexusSkills(
    private val gates: NexusGates,
    private val capture: CaptureControl,
    /** A zone pause or hold is active (zone pause records, the zone hold). The fences in [gates] are asked as well. */
    private val zonePaused: () -> Boolean,
    /** Capture is paused for any reason (user, quiet hours, glasses, zone). */
    private val paused: () -> Boolean,
    /** The hub is not running. */
    private val stopped: () -> Boolean,
    /** Saves a mark through PCA's phone-origin mark path. */
    private val mark: suspend () -> Unit,
    private val log: (String) -> Unit = {},
) : NexusSkillProvider {
    private fun zone(): Boolean = try { zonePaused() || gates.zoneFenceBlocked() || gates.askFenceBlocked() } catch (_: Exception) { true }

    private fun state(): String = when {
        stopped() -> SkillState.STOPPED
        zone() -> SkillState.ZONE_PAUSED
        paused() -> SkillState.PAUSED
        else -> SkillState.CAPTURING
    }

    override suspend fun invoke(op: SkillOp): SkillResult = try {
        when (op) {
            SkillOp.STATUS -> SkillResult.Done(state())
            SkillOp.PAUSE_CAPTURE -> when (capture.pause(UtteranceSource.PHONE_CHAT)) {
                CaptureResult.Failed -> SkillResult.Refused(SkillCodes.FAILED)
                else -> SkillResult.Done(state())
            }
            SkillOp.RESUME_CAPTURE -> when {
                zone() -> SkillResult.Refused(SkillCodes.PAUSE_ZONE) // never clears a zone pause, whatever else is paused
                else -> when (val r = capture.resume(UtteranceSource.PHONE_CHAT)) {
                    CaptureResult.Failed -> SkillResult.Refused(SkillCodes.FAILED)
                    is CaptureResult.Said -> if (r.reply == com.rokid.context.phone.voice.HubCaptureControl.ZONE_REFUSAL) SkillResult.Refused(SkillCodes.PAUSE_ZONE) else SkillResult.Done(state())
                    else -> SkillResult.Done(state())
                }
            }
            SkillOp.MARK_MOMENT -> when {
                zone() -> SkillResult.Refused(SkillCodes.PAUSE_ZONE)
                !gates.worn() -> SkillResult.Refused(SkillCodes.NOT_WORN)
                paused() -> SkillResult.Refused(SkillCodes.PAUSED)
                else -> { mark(); SkillResult.Done(state()) }
            }
        }
    } catch (c: CancellationException) { throw c } catch (e: Exception) {
        val what = op.id
        log("nexus skill failed op=$what (${e.javaClass.simpleName})")
        SkillResult.Refused(SkillCodes.FAILED)
    }
}
