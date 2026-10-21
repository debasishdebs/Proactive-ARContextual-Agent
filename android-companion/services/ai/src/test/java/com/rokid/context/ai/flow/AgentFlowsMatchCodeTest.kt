package com.rokid.context.ai.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source anchors for the conditional edges in [AgentFlows]. The graph is drawn from these code paths; if an anchor
 * disappears, the failing message names the edge to re-check against the code (and update the flow, not this test).
 */
class AgentFlowsMatchCodeTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("Run from inside android-companion: no settings.gradle.kts above ${File("").absolutePath}")
    private val orchMain = File(root, "services/orchestration/src/main/java/com/rokid/context")
    private fun src(rel: String) = File(orchMain, rel).readText()
    private val drain by lazy { src("work/QueueDrainWorker.kt") }
    /** The trust verdict, critic pass, reprocess job and nudge backstop shared by the drain job and the keyframe pipeline. */
    private val followUp by lazy { src("orchestration/ExtractFollowUp.kt") }
    private val keyframes by lazy { src("work/frames/KeyframeExtract.kt") }

    /** The body of `fun <name>(` up to the next member declaration at class indentation. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("function $name not found", start >= 0)
        val next = Regex("\\n    (private |override |internal |public )?(suspend )?fun ").find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }

    private fun anchor(edge: String, ok: Boolean) = assertTrue("Re-check edge $edge against the code: its source anchor moved", ok)

    @Test fun confidenceToCriticIsConditional() {
        anchor("capture confidence->critic (drain job)", "ExtractFollowUp.trusted(" in body(drain, "processBatchedSummarization"))
        anchor("capture confidence->critic (keyframe pipeline)", "ExtractFollowUp.modelCriticPasses(" in body(keyframes, "run"))
        anchor("capture confidence->critic \"low confidence\"", "critic.validate(" in body(followUp, "locallyTrusted") && "locallyTrusted(" in body(followUp, "trusted") && "|| modelCriticPasses(" in body(followUp, "trusted"))
        anchor("capture critic->save_review \"fail / unbound\"", "ai.bindings.get(Role.CRITIC) == null) return false" in body(followUp, "modelCriticPasses"))
    }

    @Test fun notTrustedBranchQueuesReprocess() {
        val b = body(followUp, "afterSave")
        val branch = b.indexOf("if (trusted)")
        val reprocess = b.indexOf("QueuePriority.PRIORITY_REPROCESS", branch)
        anchor("capture save_review->reprocess", branch >= 0 && reprocess > branch)
        anchor("capture save_review->reprocess (drain job)", "ExtractFollowUp.afterSave(" in body(drain, "processBatchedSummarization"))
        anchor("capture save_review->reprocess (keyframe pipeline)", "ExtractFollowUp.afterSave(" in body(keyframes, "run"))
    }

    @Test fun reprocessUsesSummarize() {
        anchor("capture reprocess [Prompt SUMMARIZE]", "PromptKey.SUMMARIZE" in body(drain, "processPriorityReprocess"))
    }

    @Test fun summarizerPassesTriggerNudge() {
        val calls = Regex("NudgeTrigger\\.afterSummarizerPass\\(").findAll(drain + followUp).count()
        anchor("capture save->to_nudge \"after a trusted pass or reprocess\"", calls >= 2)
        anchor("capture save->to_nudge (trusted pass)", "NudgeTrigger.afterSummarizerPass(" in body(followUp, "afterSave"))
        anchor("capture save->to_nudge (reprocess)", "NudgeTrigger.afterSummarizerPass(" in body(drain, "processPriorityReprocess"))
    }

    @Test fun thumbsDownRunsCriticReview() {
        anchor("capture thumbs_in->critic_review", "KIND_CRITIC_REVIEW" in body(drain, "processPriorityReprocess"))
        anchor("capture critic_review [Prompt CRITIC]", "PromptKey.CRITIC" in body(drain, "processCriticReview"))
    }

    @Test fun explicitRememberUsesItsPrompt() {
        anchor("capture remember_in->remember", "PromptKey.EXPLICIT_REMEMBER" in body(drain, "processExplicitWrite"))
    }

    @Test fun forgetResummarizes() {
        anchor("capture forget_in->resummarize", "PromptKey.SUMMARIZE" in src("orchestration/forget/ResummarizeWorker.kt"))
    }

    @Test fun nudgeInputIsATriggerNotASchedule() {
        anchor("nudge trigger (OneTime from NudgeTrigger)", "OneTimeWorkRequestBuilder<NudgeWorker>" in src("orchestration/nudge/NudgeTrigger.kt"))
        val periodic = Regex("PeriodicWorkRequest(Builder)?<[\\w.]*NudgeWorker>")
        val enqueuers = orchMain.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "NudgeWorker.kt" && "NudgeWorker" in it.readText() }.map { it.name }.toList()
        anchor("nudge trigger (no periodic NudgeWorker)", orchMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.none { periodic.containsMatchIn(it.readText()) })
        assertEquals("Re-check edge nudge trigger: NudgeWorker is enqueued outside NudgeTrigger", listOf("NudgeTrigger.kt"), enqueuers)
    }

    @Test fun nudgeProposalFollowsShouldNudge() {
        val engine = src("orchestration/nudge/NudgeEngine.kt")
        val gate = engine.indexOf("if (!out.shouldNudge")
        val propose = engine.indexOf("out.proposedAction?.let")
        anchor("nudge nudge->shown \"if shouldNudge\" / nudge->propose \"if shouldNudge and proposedAction\"", gate >= 0 && propose > gate)
    }

    @Test fun answerProposesOnlyWithProposedAction() {
        val answer = src("orchestration/AnswerService.kt")
        anchor("ask answer->propose \"if proposedAction\"", "PromptKey.ANSWER" in answer && "out.proposedAction?.let" in answer)
    }
}
