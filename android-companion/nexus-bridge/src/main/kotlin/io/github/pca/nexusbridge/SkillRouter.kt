package io.github.pca.nexusbridge

import io.github.pca.nexusport.SkillCodes
import io.github.pca.nexusport.SkillOp
import io.github.pca.nexusport.SkillResult
import io.github.pca.nexusport.SkillWire
import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.NexusLimits
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The phone's skill host as the bridge sees it (the AIDL `NexusSkillHost`, wrapped so the router is a plain JVM class). Returns the [SkillWire] string. May block. */
fun interface SkillHostClient {
    fun invoke(opId: String): String?
}

/** Whether the effect may have happened, for a Nexus `fail`: NONE = no, UNKNOWN = cannot tell (a timeout of an action). */
enum class SkillDispatchState { NONE, UNKNOWN }

sealed interface SkillReply {
    data class Complete(val state: String) : SkillReply
    data class Failed(val code: String, val dispatch: SkillDispatchState = SkillDispatchState.NONE) : SkillReply
}

/**
 * T6 on the bridge: answers one Nexus skill call. It holds no data and runs no PCA logic: it checks that the user switched skills on (the SKILLS feature bit), asks the phone's host over the port service and
 * waits at most [timeoutMs] (12 s by default; Nexus kills a call at 15 s), then maps the strict wire answer (`ok:<state>` / `fail:<code>`) to a reply. Unknown operations, a missing host, a late or malformed
 * answer all become domain failure codes, never an exception. Nothing here is logged except the operation name and a flag.
 */
class SkillRouter(
    private val host: () -> SkillHostClient?,
    private val features: () -> Int,
    private val timeoutMs: Long = NexusLimits.SKILL_HOST_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    // Two workers at most: Nexus runs one invocation per session; a hung phone cannot pile threads up.
    private val pool = ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS, SynchronousQueue()) { r -> Thread(r, "pca-skill").apply { isDaemon = true } }

    fun invoke(opId: String): SkillReply {
        val op = SkillOp.of(opId) ?: return failed(SkillCodes.UNSUPPORTED_OPERATION, null)
        if (features() and NexusFeatures.SKILLS == 0) return failed(SkillCodes.DISABLED, op)
        val h = host() ?: return failed(SkillCodes.UNAVAILABLE, op)
        val future = try {
            pool.submit(Callable { h.invoke(op.id) })
        } catch (_: RejectedExecutionException) {
            return failed(SkillCodes.BUSY, op)
        }
        val raw = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return failed(SkillCodes.DEADLINE_EXCEEDED, op, unknown = op.isAction)
        } catch (_: java.util.concurrent.ExecutionException) {
            return failed(SkillCodes.UNAVAILABLE, op, unknown = op.isAction)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failed(SkillCodes.UNAVAILABLE, op, unknown = op.isAction)
        }
        val reply = when (val r = SkillWire.decode(raw)) {
            is SkillResult.Done -> SkillReply.Complete(r.state)
            is SkillResult.Refused -> SkillReply.Failed(r.code, if (r.code == SkillCodes.INVALID_RESULT && op.isAction) SkillDispatchState.UNKNOWN else SkillDispatchState.NONE)
        }
        val answered = reply is SkillReply.Complete
        log("bridge skill op=${op.name} ok=${answered}")
        return reply
    }

    private fun failed(code: String, op: SkillOp?, unknown: Boolean = false): SkillReply {
        if (op == null) log("bridge skill unsupported") else log("bridge skill op=${op.name} ok=false")
        return SkillReply.Failed(code, if (unknown) SkillDispatchState.UNKNOWN else SkillDispatchState.NONE)
    }
}
