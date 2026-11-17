package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.SkillCodes
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T6 on the bridge: a fake phone host, the strict wire, the deadline and the default-off switch. */
class SkillRouterTest {
    private var features = NexusFeatures.SKILLS
    private var host: SkillHostClient? = SkillHostClient { "ok:capturing" }
    private val logs = mutableListOf<String>()
    private fun router(timeout: Long = 5_000) = SkillRouter({ host }, { features }, timeout, { logs += it })

    @Test fun offUntilPcaSetsTheSkillsBit() {
        features = NexusFeatures.NONE
        assertEquals(SkillReply.Failed("disabled"), router().invoke("status"))
        features = NexusFeatures.TTS or NexusFeatures.STT or NexusFeatures.ASSIST
        assertEquals(SkillReply.Failed("disabled"), router().invoke("status"))
    }

    @Test fun theFourOperationsReachThePhoneAndOthersAreUnsupported() {
        val seen = mutableListOf<String>()
        host = SkillHostClient { seen += it; "ok:paused" }
        for (op in listOf("status", "pause_capture", "resume_capture", "mark_moment")) assertEquals(SkillReply.Complete("paused"), router().invoke(op))
        assertEquals(listOf("status", "pause_capture", "resume_capture", "mark_moment"), seen)
        for (op in listOf("search_memory", "ask", "", "STATUS")) assertEquals(SkillReply.Failed("unsupported_operation"), router().invoke(op))
        assertEquals(4, seen.size)
    }

    @Test fun domainRefusalsPassThrough() {
        host = SkillHostClient { "fail:pause_zone" }
        assertEquals(SkillReply.Failed("pause_zone"), router().invoke("resume_capture"))
        host = SkillHostClient { "fail:paused" }
        assertEquals(SkillReply.Failed("paused"), router().invoke("mark_moment"))
    }

    @Test fun aMalformedAnswerIsInvalidResultAndAnUnknownEffectForActions() {
        for (bad in listOf("ok:Charminar", "fail:Home address", "free text", "", "ok:capturing\nextra")) {
            host = SkillHostClient { bad }
            assertEquals(SkillReply.Failed(SkillCodes.INVALID_RESULT), router().invoke("status"), bad)
            assertEquals(SkillReply.Failed(SkillCodes.INVALID_RESULT, SkillDispatchState.UNKNOWN), router().invoke("mark_moment"), bad)
        }
        host = SkillHostClient { null }
        assertEquals(SkillReply.Failed(SkillCodes.INVALID_RESULT), router().invoke("status"))
    }

    @Test fun noHostOrAThrowingHostIsUnavailable() {
        host = null
        assertEquals(SkillReply.Failed("unavailable"), router().invoke("status"))
        host = SkillHostClient { throw IllegalStateException("dead") }
        assertEquals(SkillReply.Failed("unavailable"), router().invoke("status"))
        assertEquals(SkillReply.Failed("unavailable", SkillDispatchState.UNKNOWN), router().invoke("pause_capture"))
    }

    @Test fun aHungPhoneAnswersWithinTheDeadlineWithADomainCode() {
        val never = CountDownLatch(1)
        host = SkillHostClient { never.await(); "ok:capturing" }
        val t0 = System.nanoTime()
        assertEquals(SkillReply.Failed("deadline_exceeded"), router(timeout = 200).invoke("status"))
        assertEquals(SkillReply.Failed("deadline_exceeded", SkillDispatchState.UNKNOWN), router(timeout = 200).invoke("pause_capture"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
        never.countDown()
        assertTrue(io.github.pca.nexusport.NexusLimits.SKILL_HOST_TIMEOUT_MS < io.github.pca.nexusport.NexusLimits.SKILL_DEADLINE_MS)
    }

    @Test fun logsCarryOnlyTheOperationAndAFlag() {
        host = SkillHostClient { "ok:capturing" }
        router().invoke("status"); router().invoke("nope")
        assertEquals(listOf("bridge skill op=STATUS ok=true", "bridge skill unsupported"), logs)
    }
}
