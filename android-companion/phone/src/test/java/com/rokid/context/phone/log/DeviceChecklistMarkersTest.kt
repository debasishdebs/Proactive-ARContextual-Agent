package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `tools/device-checklist.sh` decides PASS from log markers (`P|regex` phone logcat, `G|regex` glasses logcat, `!` = must NOT appear). A log line that is rewritten
 * (ids and text removed) silently turns a successful capture into a failed check. This test reads every positive marker and requires each of its `|` alternatives to
 * match SOME production log literal, with each `$` interpolation filled by a plausible value; so a marker whose text no longer exists in `src/main` fails here.
 */
class DeviceChecklistMarkersTest {
    private val root = File("..")
    private val script = File("../../tools/device-checklist.sh")

    private fun literals(): List<String> = listOf("glasses", "phone", "core", "services").flatMap { m ->
        File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "cpp" && it.name != "test" && it.name != "androidTest" }
            .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") }.toList()
    }.flatMap { f -> Regex(""""((?:[^"\\\n]|\\.)*)"""").findAll(f.readText()).map { it.groupValues[1] }.toList() }

    /** Every `P|..` / `G|..` positive marker of the script, each with its alternatives. */
    internal fun markers(text: String): List<Pair<String, String>> =
        Regex("""MARKERS="([^"]*)"""").findAll(text).flatMap { it.groupValues[1].split("\${NL}").asSequence() }
            .filter { it.length > 2 && (it.startsWith("P|") || it.startsWith("G|")) }
            .flatMap { m -> m.substring(2).split('|').map { alt -> m.substring(0, 1) to alt } }.toList()

    private val interp = Regex("""\$\{[^}]*\}|\$[A-Za-z_]\w*""")

    /** Every instance of [lit] with each interpolation filled (independently) from [fills]; capped, the literal's own text kept. */
    private fun instances(lit: String, fills: List<String>): List<String> {
        val unq = lit.replace("\\\"", "\"")
        val holes = interp.findAll(unq).toList()
        if (holes.isEmpty()) return listOf(unq)
        var last = 0
        val bases = mutableListOf<String>()
        for (h in holes) { bases += unq.substring(last, h.range.first); last = h.range.last + 1 }
        bases += unq.substring(last)
        var parts = listOf(bases[0])
        for (k in holes.indices) parts = parts.flatMap { p -> fills.map { v -> p + v + bases[k + 1] } }.take(2000)
        return parts
    }

    internal fun missing(markers: List<Pair<String, String>>, lits: List<String>): List<String> =
        markers.filter { (_, alt) ->
            val words = Regex("[A-Za-z_]{3,}").findAll(alt).map { it.value }.toList()
            val fills = listOf("0", "true", "x") + words
            val key = alt.takeWhile { it !in "[.*+?(" }.take(4)
            val re = Regex(alt)
            lits.none { l -> (key.isEmpty() || l.contains(key)) && instances(l, fills).any { re.containsMatchIn(it) } }
        }.map { "${it.first}|${it.second}" }.distinct()

    @Test fun everyChecklistMarkerStillMatchesAProductionLogLine() {
        assertTrue("device-checklist.sh not found from ${File(".").absolutePath}", script.isFile)
        val ms = markers(script.readText())
        assertTrue("the checklist has markers: ${ms.size}", ms.size > 20)
        assertEquals("markers with no matching log text in src/main: update the marker or keep a stable id-free marker in the log line", emptyList<String>(), missing(ms, literals()))
    }

    @Test fun theCheckCatchesARewrittenLine() {
        val ms = listOf("P" to "frame ingested ", "P" to "append result=true origin=phone", "G" to "resumed [0-9]+ pause")
        assertEquals(listOf("P|frame ingested "), missing(ms, listOf("frame ingested", "append result=\$appended origin=\$origin", "resumed \${t.clears.size} pause(s)")))
        assertEquals(emptyList<String>(), missing(ms, listOf("frame ingested \$tag", "append result=\$appended origin=\$origin", "resumed \${t.clears.size} pause(s)")))
    }

    @Test fun markersAreParsedWithEveryAlternative() {
        val ms = markers("""MARKERS="P|a b${'$'}{NL}!P|never${'$'}{NL}G|x|y${'$'}{NL}P|session closed GLASSES_EXIT|session closed USER_CLOSED" ;;""")
        assertEquals(listOf("P" to "a b", "G" to "x", "G" to "y", "P" to "session closed GLASSES_EXIT", "P" to "session closed USER_CLOSED"), ms)
    }
}
