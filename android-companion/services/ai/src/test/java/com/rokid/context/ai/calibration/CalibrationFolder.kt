package com.rokid.context.ai.calibration

import com.rokid.context.memory.retrieval.MatchCalibration
import java.io.File

/**
 * The on-disk ground truth of a calibration run, kept OUTSIDE git (a folder the owner builds from their own Moments):
 *
 *   <root>/<pin folder>/ref*.jpg|png     the photo(s) of the Moment attached to that pin (the reference the matcher sees)
 *   <root>/<pin folder>/<anything else>  photos taken AT that pin (probes whose right answer is this pin); at least one per pin
 *   <root>/_none/<any image>             photos taken where NO pin is right (probes with no truth: they can only produce a wrong card)
 *
 * A pin folder without a ref photo is skipped. Folder and file names are never printed or put in the report (only counts), so they may be real place names.
 */
class CalibrationFolder(val refs: Map<String, List<File>>, val probes: List<Pair<File, String?>>, val skippedPins: Int) {
    companion object {
        private val IMAGE = Regex(".*\\.(jpe?g|png)$", RegexOption.IGNORE_CASE)

        fun load(root: File): CalibrationFolder {
            require(root.isDirectory) { "calibration folder not found" }
            val refs = LinkedHashMap<String, List<File>>(); val probes = ArrayList<Pair<File, String?>>(); var skipped = 0
            for (dir in root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
                val imgs = dir.listFiles { f -> f.isFile && IMAGE.matches(f.name) }.orEmpty().sortedBy { it.name }
                if (dir.name == "_none") { imgs.forEach { probes += it to null }; continue }
                if (dir.name.startsWith("_") || dir.name.startsWith(".")) continue
                val (r, p) = imgs.partition { it.name.startsWith("ref", ignoreCase = true) }
                if (r.isEmpty()) { skipped++; continue }
                refs[dir.name] = r
                p.forEach { probes += it to dir.name }
            }
            return CalibrationFolder(refs, probes, skipped)
        }

        /** True when [f] sits inside a git working tree (any parent has a `.git`): a real calibration folder must not. */
        fun insideGitTree(f: File): Boolean { var d: File? = f.absoluteFile.canonicalFile; while (d != null) { if (File(d, ".git").exists()) return true; d = d.parentFile }; return false }
    }

    /** Embeds every ref and probe with [embed] (bytes -> L2 vector) and runs the calibration for one space. */
    suspend fun run(spaceName: String, embed: suspend (ByteArray) -> FloatArray): Pair<MatchCalibration.Result, String> {
        val refVecs = refs.mapValues { (_, files) -> files.map { embed(it.readBytes()) } }
        val probeObjs = probes.mapIndexed { i, (f, truth) -> MatchCalibration.Probe("probe$i", embed(f.readBytes()), truth) }
        val result = MatchCalibration.evaluate(MatchCalibration.trials(probeObjs, refVecs))
        return result to MatchCalibration.report(spaceName, result)
    }
}
