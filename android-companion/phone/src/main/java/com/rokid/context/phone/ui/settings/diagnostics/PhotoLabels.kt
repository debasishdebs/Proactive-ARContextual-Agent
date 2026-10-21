package com.rokid.context.phone.ui.settings.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's labels for the photo test: `files/photo-eval/queries.json` (app-private), appended to from the frame viewer.
 * Query text lives only in that file; nothing here logs it. The same query text merges, so one query can name several photos.
 */
class PhotoLabels(private val dir: File) {
    data class Counts(val queries: Int, val small: Int, val photos: Int)

    private val file get() = File(dir, PhotoDimEval.QUERIES_FILE)

    @Synchronized fun add(query: String, frameId: String, small: Boolean) {
        val q = query.trim(); val id = frameId.trim()
        if (q.isEmpty() || id.isEmpty()) return
        val all = read()
        var hit = false
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.getString("query") != q) continue
            hit = true
            val ids = o.getJSONArray("frameIds")
            if ((0 until ids.length()).none { ids.getString(it) == id }) ids.put(id)
            if (small) o.put("small", true)
        }
        if (!hit) all.put(JSONObject().put("query", q).put("frameIds", JSONArray().put(id)).put("small", small))
        dir.mkdirs()
        val tmp = File(dir, "${PhotoDimEval.QUERIES_FILE}.tmp")
        tmp.writeText(all.toString())
        if (!tmp.renameTo(file)) { file.writeText(all.toString()); tmp.delete() }
    }

    @Synchronized fun counts(): Counts {
        val a = read()
        val ids = HashSet<String>()
        var small = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optBoolean("small", false)) small++
            val f = o.getJSONArray("frameIds"); for (j in 0 until f.length()) ids += f.getString(j)
        }
        return Counts(a.length(), small, ids.size)
    }

    /** Deletes the labels and the last result. */
    @Synchronized fun clear() {
        file.delete(); File(dir, PhotoDimEval.RESULT_FILE).delete()
    }

    private fun read(): JSONArray = try { if (file.isFile) JSONArray(file.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
}
