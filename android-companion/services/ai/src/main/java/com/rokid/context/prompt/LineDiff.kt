package com.rokid.context.prompt

/** Line diff for prompt history (spec §4.3 "diff"): longest common subsequence, removals before additions. */
object LineDiff {
    enum class Kind { SAME, ADDED, REMOVED }
    data class Line(val kind: Kind, val text: String)

    fun diff(old: String, new: String): List<Line> {
        val a = old.lines(); val b = new.lines()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Line>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += Line(Kind.SAME, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += Line(Kind.REMOVED, a[i]); i++ }
                else -> { out += Line(Kind.ADDED, b[j]); j++ }
            }
        }
        while (i < a.size) out += Line(Kind.REMOVED, a[i++])
        while (j < b.size) out += Line(Kind.ADDED, b[j++])
        return out
    }
}
