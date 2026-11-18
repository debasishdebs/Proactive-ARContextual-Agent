package com.rokid.context.phone.canvas.api

import com.rokid.context.core.log.PcLog
import com.rokid.context.orchestration.proactive.Gate
import com.rokid.context.orchestration.proactive.ProactiveReplayer
import com.rokid.context.orchestration.proactive.ReplayStep
import com.rokid.context.orchestration.proactive.ReplayStepResult
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/**
 * `POST /api/proactive/replay` (v3.0, design 4): a DRY-RUN of the Suggestions loop over STORED data for one local day: triggers, generators, ranker and gates 4-7 as of each step, returning per step the candidates
 * (key, family, score, verdict, source ids, template text). Read-only (in `READ_ONLY_POSTS`, 30 s deadline): no suggestion row, no counter, no surface, no provider call, no network.
 *
 * Access is enforced HERE, on every call, not by the UI: Developer mode AND the default-OFF replay flag must both be on (403 otherwise), and a replay in flight is CANCELLED when either goes off. Body: `{day: "YYYY-MM-DD",
 * stepMin: 5..60 (default 15), events?: [{atMs, trigger: "place_arrival"|"moment_end"|"tick"}]}`. Logs: an event name and counts only (never an id, a key, a text or a coordinate).
 */
class ProactiveReplayRoute(
    private val devMode: StateFlow<Boolean>,
    private val flag: StateFlow<Boolean>,
    private val replay: suspend (day: LocalDate, stepMin: Int, extra: List<ReplayStep>) -> List<ReplayStepResult>,
    private val deadlineMs: Long = DEADLINE_MS,
    /** v3.1: the store's plain counters, read only to report the DELIVERY counters (`deliver_*`, `drop_delivery_*`, `fb_*`) next to the replay. The replay itself delivers nothing. */
    private val counters: () -> Map<String, Int> = { emptyMap() },
) {
    private val running = Mutex()

    fun allowed(): Boolean = devMode.value && flag.value

    private fun denied(): HttpResponse = HttpResponse.error(403, "developer_mode_off", if (devMode.value) "This replay is switched off." else "Developer mode is off.")
    private fun bad() = HttpResponse.error(400, "bad_request", "Bad request.")

    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != PATH) return null
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!allowed()) return denied()
        if (req.bodyTooLarge || req.body.size > MAX_BYTES) return HttpResponse.error(400, "too_large", "The request is too large.")
        val body = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return bad()
        val day = (body["day"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return bad()
        val stepMin = body["stepMin"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: return bad() } ?: 15
        if (stepMin !in 5..60) return bad()
        val extra = ArrayList<ReplayStep>()
        val events = body["events"]
        if (events != null) {
            val arr = events as? JsonArray ?: return bad()
            if (arr.size > MAX_EVENTS) return bad()
            for (e in arr) {
                val o = e as? JsonObject ?: return bad()
                val at = (o["atMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return bad()
                val kind = TriggerKind.of((o["trigger"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return bad()
                extra += ReplayStep(at, kind)
            }
        }
        if (!running.tryLock()) return HttpResponse.error(409, "busy", "A replay is already running.")
        val started = System.nanoTime()
        try {
            val result = try {
                coroutineScope {
                    val work = async { withTimeoutOrNull(deadlineMs) { replay(day, stepMin, extra) } }
                    // Developer mode or the flag going off cancels the replay in flight.
                    val watch = launch { combine(devMode, flag) { a, b -> a && b }.first { !it }; work.cancel(CancellationException("disabled")) }
                    try { work.await() } finally { watch.cancel() }
                }
            } catch (e: CancellationException) {
                if (!allowed()) return denied()
                throw e
            }
            if (!allowed()) return denied()
            if (result == null) { PcLog.i("proactive_replay", "replay deadline"); return HttpResponse.error(503, "busy", "The replay took too long.") }
            val n = result.size
            PcLog.i("proactive_replay", "replay done steps=$n")
            return HttpResponse.json(200, answer(day, stepMin, result, stepsRun = 1_440 / stepMin + extra.size, elapsedMs = (System.nanoTime() - started) / 1_000_000))
        } catch (e: CancellationException) { throw e
        } catch (e: com.rokid.context.orchestration.DataOpGate.LeaseBlocked) {
            PcLog.i("proactive_replay", "replay blocked by a delete"); return HttpResponse.error(503, "busy", "A delete is running, try again.")
        } catch (e: Throwable) {
            PcLog.i("proactive_replay", "replay failed (${e.javaClass.simpleName})")
            return HttpResponse.error(500, "internal", "Something went wrong.")
        } finally { running.unlock() }
    }

    private fun answer(day: LocalDate, stepMin: Int, steps: List<ReplayStepResult>, stepsRun: Int, elapsedMs: Long): JsonObject = buildJsonObject {
        put("ok", true); put("day", day.toString()); put("stepMin", stepMin); put("stepsRun", stepsRun); put("elapsedMs", elapsedMs)
        putJsonArray("notReplayed") { ProactiveReplayer.NOT_REPLAYED.forEach { add(JsonPrimitive(it.name)) } }
        putJsonArray("steps") {
            steps.take(ProactiveReplayer.MAX_STEPS).forEach { s ->
                add(buildJsonObject {
                    put("atMs", s.atMs); put("trigger", s.trigger.token); put("gate", s.gate.name)
                    putJsonArray("candidates") {
                        s.candidates.forEach { c ->
                            add(buildJsonObject {
                                put("key", c.key); put("family", c.family); put("score", c.score); put("verdict", c.verdict.name)
                                putJsonArray("sourceIds") { c.sourceIds.forEach { add(JsonPrimitive(it)) } }
                                c.text?.let { put("text", it) }
                            })
                        }
                    }
                })
            }
        }
        put("wouldShow", steps.sumOf { s -> s.candidates.count { it.verdict == Gate.WOULD_SHOW } })
        // v3.1: what the live loop did on this phone so far (counts only; the replay is a dry run and never delivers)
        val c = runCatching { counters() }.getOrDefault(emptyMap())
        put("delivery", buildJsonObject {
            put("delivered", buildJsonObject { c.filterKeys { it.startsWith("deliver_") }.toSortedMap().forEach { (k, v) -> put(k.removePrefix("deliver_"), v) } })
            put("dropped", buildJsonObject { c.filterKeys { it.startsWith("drop_delivery_") }.toSortedMap().forEach { (k, v) -> put(k.removePrefix("drop_delivery_"), v) } })
            put("feedback", buildJsonObject { c.filterKeys { it.startsWith("fb_") }.toSortedMap().forEach { (k, v) -> put(k.removePrefix("fb_"), v) } })
        })
    }

    companion object {
        const val PATH = "/api/proactive/replay"
        const val DEADLINE_MS = 30_000L
        const val MAX_BYTES = 4_096
        const val MAX_EVENTS = 20
        private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    }
}
