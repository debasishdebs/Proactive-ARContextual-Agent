package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.plan.DerivedSignals
import com.rokid.context.ai.canvas.plan.ExprRole
import com.rokid.context.ai.canvas.plan.ExprSeries
import com.rokid.context.ai.canvas.plan.ExprTry
import com.rokid.context.ai.canvas.plan.SignalConfig
import com.rokid.context.ai.canvas.plan.WindowTolerance
import com.rokid.context.canvas.signal.SignalTryReader
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.ThermalStatusProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.flow.StateFlow

/**
 * `POST /api/signals/try` (v1.2 row 2): evaluates the UNSAVED definition in the request over the LATEST IMPORTED RECORDING's per-second features ([SignalTryReader]) and answers raw numbers ("Raw expression output").
 * Read-only (in `READ_ONLY_POSTS`, 30 s deadline), zero model calls, no network, nothing persisted: it reads no store but the current signal config and the sensor rows, and the route has no model, hub or plan dependency.
 *
 * Access is enforced HERE, on every call, not by the UI: Developer mode AND the default-OFF flag must both be on (403 otherwise), and a try in flight is CANCELLED when either goes off. Refusals: a body over
 * [ExprTry.MAX_BYTES] or not an object (400), a window other than 5/10/30 minutes (400), thermal SEVERE or worse (503), one try at a time (409), at most one per [MIN_GAP_MS] (429), a Forget or Delete all during the
 * read (409 `forgotten`, nothing shown). Logs: an event name and counts only.
 */
class SignalTry(
    private val reader: SignalTryReader,
    private val config: () -> SignalConfig,
    private val devMode: StateFlow<Boolean>,
    private val flag: StateFlow<Boolean>,
    private val thermal: ThermalStatusProvider = ThermalStatusProvider { PhoneThermal.NONE },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val running = Mutex()
    @Volatile private var lastMs = Long.MIN_VALUE / 2

    fun allowed(): Boolean = devMode.value && flag.value

    private fun denied(): HttpResponse = HttpResponse.error(403, "developer_mode_off", if (devMode.value) "This preview is switched off." else "Developer mode is off.")

    suspend fun handle(req: HttpRequest): HttpResponse {
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!allowed()) return denied()
        if (req.bodyTooLarge || req.body.size > ExprTry.MAX_BYTES) return HttpResponse.error(400, "too_large", "The request is too large.")
        val body = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        val seconds = body["seconds"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: return HttpResponse.error(400, "bad_value", BAD_SECONDS) } ?: DEFAULT_SECONDS
        if (seconds !in CHOICES) return HttpResponse.error(400, "bad_value", BAD_SECONDS)
        val graph = body["graph"] as? JsonObject ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        val name = (body["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "tryit"
        val cfg = config()
        DerivedSignals.validate(name, graph, cfg).takeIf { it.isNotEmpty() }?.let { return problems(it) }
        if (thermal.status().ordinal >= PhoneThermal.SEVERE.ordinal) return HttpResponse.error(503, "busy", "The phone is hot.")
        if (!running.tryLock()) return HttpResponse.error(409, "busy", "A try is already running.")
        try {
            val now = clock()
            if (now - lastMs < MIN_GAP_MS) return HttpResponse.error(429, "too_soon", "Wait a moment before trying again.")
            lastMs = now
            val pct = WindowTolerance.current()
            val out = try {
                coroutineScope {
                    val work = async { reader.read(seconds) { s -> ExprSeries.run(graph, s.rows, s.fromS, s.seconds, pct, cfg) } }
                    // Developer mode or the flag going off cancels the try in flight.
                    val watch = launch { combine(devMode, flag) { a, b -> a && b }.first { !it }; work.cancel(CancellationException("disabled")) }
                    try { work.await() } finally { watch.cancel() }
                }
            } catch (e: CancellationException) {
                if (!allowed()) return denied()
                throw e
            }
            if (!allowed()) return denied()
            return answer(out)
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            PcLog.i("signaltry", "try failed (${e.javaClass.simpleName})")
            return HttpResponse.error(500, "internal", "Something went wrong.")
        } finally { running.unlock() }
    }

    private fun problems(p: List<String>) = HttpResponse.json(200, buildJsonObject { put("ok", false); putJsonArray("problems") { p.forEach { add(JsonPrimitive(it)) } } })

    private fun answer(out: SignalTryReader.Outcome<ExprSeries.Result>): HttpResponse = when (out) {
        is SignalTryReader.Outcome.Busy -> { PcLog.i("signaltry", "try refused: delete running"); HttpResponse.error(409, "busy", "A delete is running. Try again.") }
        is SignalTryReader.Outcome.Forgotten -> { PcLog.i("signaltry", "try refused: forgotten"); HttpResponse.error(409, "forgotten", "Some of this data was forgotten while it ran. Nothing is shown.") }
        is SignalTryReader.Outcome.Unavailable -> {
            val reason = out.reason
            PcLog.i("signaltry", "try unavailable reason=$reason")
            HttpResponse.json(200, buildJsonObject {
                put("ok", true); put("available", false); put("reason", reason); put("message", MESSAGES[reason] ?: "Preview unavailable.")
                out.snapshot?.let { s -> put("source", SOURCE_LABEL); putJsonObject("window") { windowFields(s, 0) } }
            })
        }
        is SignalTryReader.Outcome.Done -> when (val r = out.value) {
            is ExprSeries.Result.Problems -> problems(r.problems)
            is ExprSeries.Result.Bad -> HttpResponse.error(400, r.code, r.msg)
            is ExprSeries.Result.Ok -> {
                val sm = r.summary
                val n = sm.valid
                val missing = sm.missing
                PcLog.i("signaltry", "try done n=$n missing=$missing")
                HttpResponse.json(200, buildJsonObject {
                    put("ok", true); put("available", true); put("role", if (r.role == ExprRole.GUARD) "guard" else "value")
                    put("source", SOURCE_LABEL); put("output", "Raw expression output")
                    putJsonObject("recording") {
                        val s = out.snapshot
                        put("fromMs", s.recordedFromMs); s.recordedToMs?.let { put("toMs", it) }
                        put("coverageFromS", s.coverageFromS); put("coverageToS", s.coverageToS); put("coverageSeconds", s.coverageSeconds)
                    }
                    putJsonObject("window") { windowFields(out.snapshot, sm.covered) }
                    putJsonObject("summary") {
                        put("valid", sm.valid); put("denominator", sm.valid); put("trueCount", sm.trueCount)
                        sm.trueFrac?.let { put("trueFrac", it) }
                        sm.min?.let { put("min", it); put("max", sm.max!!); put("mean", sm.mean!!) }
                        putJsonObject("states") { sm.states.forEach { (k, v) -> put(k, v) } }
                        put("contextDefaulted", sm.contextDefaulted)
                    }
                    putJsonArray("points") {
                        r.points.forEach { p -> add(buildJsonObject {
                            put("s", p.second); put("state", p.state); p.value?.let { put("value", it) }; p.min?.let { put("min", it) }; p.max?.let { put("max", it) }; p.flag?.let { put("flag", it) }
                        }) }
                    }
                    putJsonArray("moments") { out.snapshot.moments.forEach { m -> add(buildJsonObject { put("id", m.id); put("s", m.second) }) } }
                    put("momentsTruncated", out.snapshot.momentsTruncated); put("truncated", r.truncated)
                    put("windowStateBytes", r.windowStateBytes); put("windowTerms", r.windowTerms)
                })
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.windowFields(s: SignalTryReader.Snapshot, covered: Int) {
        put("fromS", s.fromS); put("toS", s.toS); put("seconds", s.seconds); put("covered", covered); put("missing", s.seconds - covered)
        put("excludedZoneHeld", s.zoneHeld); put("excludedZoneUnknown", s.zoneUnknown)
    }

    companion object {
        const val SOURCE = "imported_trace"
        const val SOURCE_LABEL = "Latest imported recording"
        const val DEFAULT_SECONDS = 600
        val CHOICES = setOf(300, 600, 1800)
        const val MIN_GAP_MS = 2_000L
        private const val BAD_SECONDS = "The window is 5, 10 or 30 minutes."
        val MESSAGES = mapOf(
            "no_sensor_data" to "No recorded motion data yet. Import a trace (Developer mode) to try on real data.",
            "no_zone_history" to "Preview unavailable: no zone history.",
            "zone_held" to "Preview unavailable: every second of this window was recorded in a privacy zone.",
        )
    }
}
