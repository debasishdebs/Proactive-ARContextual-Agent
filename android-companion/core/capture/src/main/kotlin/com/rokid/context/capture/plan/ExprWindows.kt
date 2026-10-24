package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.graph.WindowRing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The window terms of lowered programs (3c C1b/X6): `{op:win, name, agg, sec[, t]}` entries over the seven one-second features. A term is the distinct
 * (signal, aggregate, seconds, threshold) over all programs of a plan: the same term used twice shares one ring. At most [WindowRing.MAX_TERMS] terms,
 * 1 to 300 s; their ring bytes count toward the plan size cap. One place parses them, so the validator, the runner and the lowering agree.
 */
object ExprWindows {
    /** [thr] is the quantised threshold of a `frac` term (a second counts when its quantised value is above it), else 0. */
    data class Term(val feature: Int, val name: String, val agg: Int, val seconds: Int, val thr: Int) {
        val stateBytes: Int get() = WindowRing.stateBytes(seconds, agg)
    }

    /** The distinct terms in first-use order, or null when an entry is malformed (unknown signal, aggregate, horizon). Entries that are not windows are ignored. */
    fun termsOf(exprs: List<JsonObject>): List<Term>? {
        val out = LinkedHashSet<Term>()
        for (p in exprs) for (e in (p["s"] as? JsonArray) ?: continue) {
            val o = e as? JsonObject ?: return null
            if ((o["op"] as? JsonPrimitive)?.content != "win") continue
            out += term(o) ?: return null
        }
        return out.toList()
    }

    fun term(o: JsonObject): Term? {
        val name = SignalCatalog.canonical((o["name"] as? JsonPrimitive)?.content ?: return null) ?: return null
        val feature = SignalCatalog.featureIndex(name) ?: return null   // only the one-second features, never ctx or ctxAgeS
        val agg = WindowRing.aggCode((o["agg"] as? JsonPrimitive)?.content ?: return null) ?: return null
        val sec = (o["sec"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in WindowRing.MIN_SECONDS..WindowRing.MAX_SECONDS } ?: return null
        val thr = if (agg == WindowRing.AGG_FRAC) {
            WindowRing.quantise((o["t"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null) ?: return null
        } else {
            if ("t" in o) return null
            0
        }
        return Term(feature, name, agg, sec, thr)
    }

    fun stateBytes(terms: List<Term>): Int = terms.sumOf { it.stateBytes }

    /** True when the plan needs the `sig2` catalogue: a window term or a value program (an old runner would silently skip them). */
    fun needsSig2(exprs: List<JsonObject>): Boolean = exprs.any { p ->
        (p["role"] as? JsonPrimitive)?.content == "value" ||
            ((p["s"] as? JsonArray)?.any { ((it as? JsonObject)?.get("op") as? JsonPrimitive)?.content == "win" } == true)
    }
}
