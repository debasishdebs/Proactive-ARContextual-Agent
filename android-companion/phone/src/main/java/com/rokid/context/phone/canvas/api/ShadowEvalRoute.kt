package com.rokid.context.phone.canvas.api

import com.rokid.context.core.log.PcLog
import com.rokid.context.orchestration.proactive.ShadowEval
import com.rokid.context.orchestration.proactive.ShadowEvalInput
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * `GET /api/proactive/shadow-eval` (Developer only, text/plain): the 7-day shadow-week report ([ShadowEval], counts and fixed labels only) built from the real stores. READ-ONLY: it writes nothing, delivers nothing and works
 * with every delivery switch OFF. Access is enforced HERE on every call: Developer mode off answers 403 `developer_mode_off`, exactly like `POST /api/proactive/replay` (the replay's own default-OFF flag is not needed to read counts).
 *
 * [input] builds the [ShadowEvalInput] from the live stores (a suspend lambda; production in `CanvasExtras`). The body is `EvalReport.toText()` plus fixed NOTE lines saying what this build cannot measure. Logs: a literal and a count.
 */
class ShadowEvalRoute(
    private val devMode: StateFlow<Boolean>,
    private val input: suspend () -> ShadowEvalInput,
) {
    private fun denied() = HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")

    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != PATH) return null
        if (!req.method.equals("GET", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!devMode.value) return denied()
        return try {
            val report = ShadowEval.evaluate(input())
            if (!devMode.value) return denied()
            val n = report.observedDays
            PcLog.i("shadow_eval", "report built days=$n")
            val text = report.toText() + "\n" + NOTES.joinToString("\n")
            HttpResponse(200, TEXT, text.toByteArray(Charsets.UTF_8), mapOf("Cache-Control" to "no-store"))
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            PcLog.i("shadow_eval", "report failed (${e.javaClass.simpleName})")
            HttpResponse.error(500, "internal", "Something went wrong.")
        }
    }

    companion object {
        const val PATH = "/api/proactive/shadow-eval"
        const val TEXT = "text/plain; charset=utf-8"
        /** Fixed lines: the store keeps cumulative counters only, and driving and wear have no history. */
        val NOTES = listOf(
            "note: refused_by_gate and runs are CUMULATIVE counters since install or the last Delete all (the store keeps no per-day histogram), shown as the overall histogram; the day lines carry would-show counts only.",
            "note: quiet and zone are checked against the stored pause history; driving and unworn keep no history, so they stay UNCHECKED and the verdict cannot be PASS until a build records them.",
        )
    }
}
