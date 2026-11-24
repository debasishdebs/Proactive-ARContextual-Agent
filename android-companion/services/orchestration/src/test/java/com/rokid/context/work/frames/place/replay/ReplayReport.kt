package com.rokid.context.work.frames.place.replay

import com.rokid.context.work.frames.place.PlaceMatchResult
import com.rokid.context.work.frames.place.PlaceOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A baseline run (HEAD) and the same run with each overlay, for the side-by-side per-place table. */
data class ReplayGroup(val title: String, val head: ReplayResult, val overlays: Map<String, ReplayResult> = emptyMap())

data class Calibration(val checked: Int, val reproduced: Int, val notCalibratable: Int, val misses: List<String>, val trusted: Boolean) {
    fun line() = "historical records: ${checked + notCalibratable}, with a position: $checked, reproduced as MATCHED: $reproduced, without a position (not calibratable): $notCalibratable, " +
        "other: ${misses.size} -> ${if (trusted) "TRUSTED" else "UNTRUSTED"}"
}

/**
 * The report of a replay (plan 7). It holds real places, frame ids and times, so it is written ONLY outside the repo ([ReplayEnv.outDir] refuses a repo path) and only when a real bundle was given.
 */
object ReplayReport {
    private val IST = ZoneId.of("Asia/Kolkata")
    private val CLOCK = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")
    fun ist(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(IST))

    val LIMITS = listOf(
        "BUSY is not reproduced: the fakes return at once (production logged 60 BUSY).",
        "Pokes are approximate: one per frame_meta row at its createdAt (production: once per committed frame).",
        "Media EXIF has no accuracy: accuracy comes from the scenario, never from the data.",
        "Tombstones are replayed by time; retention tombstones made after the trip have no effect.",
        "The trip's place pack is lost (places.db is encrypted and is not read): Salar Jung / Golconda / Ramoji entries exist only as ASSUMED sites, and only in calibration runs.",
        "ASSUMED positions (pin or site centre) are labelled on every row and never used for GPS backfill.",
        "The real-model stage is OFF and not implemented (local only); Oracle and Historical stand in for the model.",
        "Founders-gallery truth window (21 Nov 17:19-17:27) is the weakest truth: low confidence.",
    )

    private val ALLOWED_DIVERGENCE = setOf("same_place_recently", "call_gap_20s")

    /** Plan 8: the Historical stage must reproduce the trip's real MATCHED results (those that have a position in the track). */
    fun calibrate(bundle: ReplayBundle, r: ReplayResult): Calibration {
        var checked = 0; var ok = 0; var none = 0; val misses = ArrayList<String>()
        for (h in bundle.historical) {
            val row = r.rows.firstOrNull { it.frameId == h.frameId && it.extra.isEmpty() && it.outcome != PlaceOutcome.SEEN }
            if (row == null || row.pos == "UNKNOWN" || row.pos == "-") { none++; continue }
            checked++
            // H2: a MATCHED whose card was suppressed (`cardWhy=already_shown_here`) is still the recorded identification.
            if (row.outcome == PlaceOutcome.MATCHED && row.chosenName.equals(h.name, ignoreCase = true)) ok++
            else misses += "${ist(h.capturedAt)} ${h.name}: ${row.verdict}${if (row.note.isNotEmpty()) " (${row.note})" else ""}"
        }
        val allowed = misses.count { m -> ALLOWED_DIVERGENCE.any { m.contains(it) } }
        val trusted = checked > 0 && ok >= (checked * 0.6).let { Math.ceil(it).toInt() } && allowed == misses.size
        return Calibration(checked, ok, none, misses, trusted)
    }

    /** Wrong-place cards: a published card (or an F1 card) for a place other than the truth span's target, at a frame inside a span. */
    fun wrongCards(bundle: ReplayBundle, r: ReplayResult): List<ReplayRow> = r.rows.filter { row ->
        val span = row.frameCapMs?.let { bundle.spanAt(it) } ?: return@filter false
        val target = span.targetName
        // H3: a cluster card is wrong only when the span's target is not a member of it.
        (row.isCard && (target == null || target !in row.names)) || (row.f1.startsWith("card ") && row.f1.removePrefix("card ") != target)
    }

    fun spanVerdict(span: TruthSpan, rows: List<ReplayRow>): String {
        if (!span.isTestable && !span.assumedFixOnly) return "not testable (${span.testable.substringAfter(':', span.testable)}; ${span.label})"
        val inSpan = rows.filter { r -> r.frameCapMs?.let { it in span } == true }
        val cards = inSpan.count { it.isCard && span.targetName in it.names }
        val wrong = inSpan.count { it.isCard && span.targetName !in it.names }
        val top = inSpan.groupingBy { it.verdict }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString("; ") { "${it.key} x${it.value}" }
        val tag = if (span.assumedFixOnly) "ASSUMED fix only; " else ""
        return "${tag}pokes=${inSpan.size} cards=$cards wrong=$wrong | $top"
    }

    private fun cell(head: ReplayRow?, o: ReplayRow?): String = when {
        o == null -> "-"
        head != null && head.verdict == o.verdict && head.chosenId == o.chosenId -> "="
        else -> o.verdict + if (o.isCard) " [${o.chosenName}]" else ""
    }

    private fun headCell(r: ReplayRow) = r.verdict + if (r.isCard) " [${r.chosenName}]" else if (r.note.isNotEmpty()) " (${r.note})" else ""

    /** The per-place table for one group (plan 7): consecutive SEEN / NO_FRAME rows are collapsed. */
    fun placeTable(span: TruthSpan, g: ReplayGroup): String {
        val cols = listOf("F1", "F2", "ALL")
        val byOverlay = g.overlays.mapValues { (_, res) -> res.rows.filter { it.extra.isEmpty() }.associateBy { it.pokeMs } }
        val head = g.head.rows.filter { it.extra.isEmpty() && it.frameCapMs?.let { c -> c in span } == true }
        val sb = StringBuilder("| IST | Frame | Motion | Pos | HEAD | ${cols.joinToString(" | ")} | Truth |\n|---|---|---|---|---|${cols.joinToString("|") { "---" }}|---|\n")
        var i = 0
        while (i < head.size) {
            val r = head[i]
            var j = i
            if (r.outcome == PlaceOutcome.SEEN || r.outcome == PlaceOutcome.NO_FRAME) while (j + 1 < head.size && head[j + 1].verdict == r.verdict) j++
            val n = j - i + 1
            val cells = cols.map { c ->
                val o = byOverlay[c]?.get(r.pokeMs)
                if (c == "F1") (byOverlay[c]?.get(r.pokeMs)?.f1 ?: "-").let { d -> if (d == "-") "-" else d } else cell(r, o)
            }
            sb.append("| ${ist(r.pokeMs)}${if (n > 1) " .. ${ist(head[j].pokeMs)}" else ""} | ${r.frameId?.takeLast(6) ?: "-"} | ${r.motion} | ${r.pos}${if (r.assumed) " (ASSUMED)" else ""} | ${headCell(r)}${if (n > 1) " x$n" else ""} | ${cells.joinToString(" | ")} | ${span.label} |\n")
            i = j + 1
        }
        for (e in g.head.rows.filter { it.extra.isNotEmpty() && it.frameCapMs?.let { c -> c in span } == true })
            sb.append("| ${ist(e.pokeMs)} ${e.extra} | ${e.frameId?.takeLast(6) ?: "-"} | ${e.motion} | ${e.pos} | ${e.verdict}${if (e.isCard) " [${e.chosenName}]" else ""} | ${cols.joinToString(" | ") { "-" }} | ${span.label} |\n")
        return sb.toString()
    }

    private fun histogram(r: ReplayResult) = r.rows.groupingBy { it.verdict }.eachCount().entries.sortedByDescending { it.value }.take(14).joinToString("\n") { "- ${it.key}: ${it.value}" }

    /** H5: `calls=<n> pinCards=<n> clusterCards=<n> suppressed=<n>` of a whole run. */
    fun totals(r: ReplayResult) = "calls=${r.rows.count { it.isCall }} pinCards=${r.rows.count { it.isPinCard }} clusterCards=${r.rows.count { it.isClusterCard }} suppressed=${r.rows.count { it.isSuppressed }}"

    /** H5: one tab-separated row per (run, overlay, span) plus a `*` row of the whole run, so a step can compare numbers by script. Counts only. */
    fun acceptance(bundle: ReplayBundle, groups: List<ReplayGroup>): String {
        val sb = StringBuilder("run\toverlay\tspan\tcards\twrong\tcalls\tpinCards\tclusterCards\n")
        for (g in groups) for ((name, r) in listOf("HEAD" to g.head) + g.overlays.map { it.key to it.value }) {
            for (span in bundle.truth) {
                val rows = r.rows.filter { it.frameCapMs?.let { c -> c in span } == true }
                sb.append("${g.title}\t$name\t${span.label}\t${rows.count { it.isCard && span.targetName in it.names }}\t${rows.count { it.isCard && span.targetName !in it.names }}\t${rows.count { it.isCall }}\t${rows.count { it.isPinCard }}\t${rows.count { it.isClusterCard }}\n")
            }
            sb.append("${g.title}\t$name\t*\t${r.rows.count { it.isCard }}\t${wrongCards(bundle, r).size}\t${r.rows.count { it.isCall }}\t${r.rows.count { it.isPinCard }}\t${r.rows.count { it.isClusterCard }}\n")
        }
        return sb.toString()
    }

    fun summary(bundle: ReplayBundle, groups: List<ReplayGroup>, calibration: Calibration?): String {
        val sb = StringBuilder("# Trip replay summary\n\nREAL DATA. This folder stays on this Mac (never in git).\n\n## Fidelity limits\n")
        LIMITS.forEach { sb.append("- $it\n") }
        sb.append("\n## Calibration (Historical stage, assumed sites)\n${calibration?.line() ?: "not run"}\n")
        calibration?.misses?.forEach { sb.append("- not reproduced: $it\n") }
        if (calibration != null && !calibration.trusted) sb.append("\n**Every verdict below is UNTRUSTED (calibration failed).**\n")
        sb.append("\nKnown divergence (printed, not a failure): a constant ASSUMED fix makes a second card within 120 s / 30 m a DUPLICATE where the real phone had moved.\n")
        for (g in groups) {
            sb.append("\n## ${g.title}\n")
            sb.append("Outcomes (all ${g.head.rows.size} pokes), HEAD:\n${histogram(g.head)}\n\n")
            sb.append("Wrong-place cards: HEAD ${wrongCards(bundle, g.head).size}" + g.overlays.entries.joinToString("") { ", ${it.key} ${wrongCards(bundle, it.value).size}" } + "\n")
            sb.append("Run totals: HEAD ${totals(g.head)}" + g.overlays.entries.joinToString("") { ", ${it.key} ${totals(it.value)}" } + "\n\n")
            for (span in bundle.truth) {
                val cards = { r: ReplayResult -> r.rows.count { it.isCard && it.frameCapMs?.let { c -> c in span } == true && span.targetName in it.names } }
                val f1Cards = { r: ReplayResult -> r.rows.count { it.extra.isEmpty() && it.f1.startsWith("card ") && it.frameCapMs?.let { c -> c in span } == true && it.f1 == "card ${span.targetName}" } }
                sb.append("- ${span.label}: ${spanVerdict(span, g.head.rows)}")
                if (span.isTestable || span.assumedFixOnly) sb.append(" | cards HEAD ${cards(g.head)}" + g.overlays.entries.joinToString("") { (k, v) -> ", $k ${cards(v)}${if (k == "F1" || k == "ALL") "/F1 card ${f1Cards(v)}" else ""}" })
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun write(outDir: File, bundle: ReplayBundle, groups: List<ReplayGroup>, calibration: Calibration?) {
        outDir.mkdirs()
        File(outDir, "summary.md").writeText(summary(bundle, groups, calibration))
        File(outDir, "acceptance.tsv").writeText(acceptance(bundle, groups))
        for (span in bundle.truth) {
            val sb = StringBuilder("# ${span.label} (${ist(span.startMs)} - ${ist(span.endMs)}), target ${span.target}\n\n${span.testable.let { if (it == "yes") "" else "Testable: $it\n\n" }}")
            for (g in groups) sb.append("## ${g.title}\n\n${spanVerdict(span, g.head.rows)}\n\n${placeTable(span, g)}\n")
            File(outDir, "place-${span.label}.md").writeText(sb.toString())
        }
        File(outDir, "attempts.log").writeText(groups.joinToString("\n") { g -> "# ${g.title}\n" + g.head.rows.joinToString("\n") { it.attemptLine } })
        val seen = HashSet<String>(); val req = StringBuilder("frameId\tlines\n")
        for (g in groups) for (q in g.head.requests) if (seen.add(q.frameId + q.lines.joinToString("|"))) req.append("${q.frameId}\t${q.lines.joinToString(" ; ")}\n")
        File(outDir, "requests.tsv").writeText(req.toString())
        outDir.walkTopDown().forEach { it.setReadable(false, false); it.setWritable(false, false); it.setExecutable(false, false); it.setReadable(true, true); it.setWritable(true, true); if (it.isDirectory) it.setExecutable(true, true) }
        outDir.parentFile?.let { it.setReadable(false, false); it.setExecutable(false, false); it.setReadable(true, true); it.setWritable(true, true); it.setExecutable(true, true) }
    }

    /** The default set of runs (plan 7). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun defaultGroups(bundle: ReplayBundle, scope: TestScope): Pair<List<ReplayGroup>, Calibration?> {
        val runner = TripReplayRunner(bundle)
        val sites = bundle.sites.isNotEmpty()
        fun group(title: String, stage: MatchStage, sc: FixScenario, act: Activation = Activation.AS_CREATED, withSites: Boolean = false, overlays: Boolean = true): ReplayGroup {
            val head = runner.run(scope, RunSpec(stage, sc, act, Overlays.HEAD, sites = withSites, legacyDedupe = stage === Historical))
            val ov = if (!overlays) emptyMap() else linkedMapOf(
                "F1" to Overlays.F1, "F2" to Overlays.f2(10), "ALL" to Overlays.ALL,
            ).mapValues { (_, o) -> runner.run(scope, RunSpec(if (o == Overlays.ALL && stage === Oracle) CaptionTextOrOracle else stage, sc, act, o, sites = withSites)) }
            return ReplayGroup(title, head, ov)
        }
        val groups = ArrayList<ReplayGroup>()
        for (sc in listOf(FixScenario.AsRecorded, FixScenario.Outdoor10, FixScenario.Indoor60, FixScenario.Stale, FixScenario.IndoorMediaGood)) groups += group("Oracle x ${sc.label} x AS_CREATED", Oracle, sc)
        groups += group("Oracle x ASSUMED_PIN_10M (Antera/Ramoji, sites) x AS_CREATED", Oracle, FixScenario.AssumedPin(10f), withSites = sites)
        groups += group("Oracle x ASSUMED_PIN_60M (Antera/Ramoji, sites) x AS_CREATED", Oracle, FixScenario.AssumedPin(60f), withSites = sites)
        groups += group("Oracle x OUTDOOR_10M x ALWAYS (next visit)", Oracle, FixScenario.Outdoor10, Activation.ALWAYS)
        groups += group("Historical x OUTDOOR_10M x AS_CREATED", Historical, FixScenario.Outdoor10, overlays = false)
        groups += group("StubNone x OUTDOOR_10M x AS_CREATED", StubNone, FixScenario.Outdoor10, overlays = false)
        val cal = group("CALIBRATION Historical x ASSUMED_PIN_10M +sites x AS_CREATED", Historical, FixScenario.AssumedPin(10f), withSites = sites, overlays = false)
        groups += cal
        return groups to calibrate(bundle, cal.head)
    }

    /** ALL = F1+F2 with the F5 text matcher where it has a hit (H1): a frame whose caption names no candidate falls through to the oracle. */
    internal object CaptionTextOrOracle : MatchStage {
        override val label = "CaptionText+Oracle"
        override fun create(bundle: ReplayBundle): ReplayMatcher {
            val c = CaptionText.create(bundle); val o = Oracle.create(bundle)
            return object : BaseMatcher() {
                override suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult {
                    c.note = null
                    val r = c.match(frameId, lines)
                    if (c.note != "caption_no_hit") { note = c.note; return r }
                    return o.match(frameId, lines).also { note = o.note }
                }
            }
        }
    }
}

/** Where the real-data test reads and writes. Real data never enters git (R1). */
object ReplayEnv {
    fun bundleDir(): File? = (System.getProperty("replay.bundle") ?: System.getenv("REPLAY_BUNDLE"))?.takeIf { it.isNotBlank() }?.let { File(it.replaceFirst("~", System.getProperty("user.home"))) }?.takeIf { File(it, "frames.tsv").isFile }

    /** True when [dir] lies inside a git work tree (a `.git` file or folder in it or any parent). */
    fun insideGitTree(dir: File): Boolean {
        var d: File? = dir.absoluteFile.canonicalFile
        while (d != null) { if (File(d, ".git").exists()) return true; d = d.parentFile }
        return false
    }

    /** The report folder, refused when it is inside the repo. */
    fun outDir(path: String?): File {
        val p = path?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("REPLAY_OUT (or -Dreplay.out) must be set to a folder outside the repo")
        val d = File(p.replaceFirst("~", System.getProperty("user.home")))
        require(!insideGitTree(d)) { "REPLAY_OUT must be outside any git work tree (real data never enters git)" }
        return d
    }

    fun configuredOut(): String? = System.getProperty("replay.out") ?: System.getenv("REPLAY_OUT")
}
