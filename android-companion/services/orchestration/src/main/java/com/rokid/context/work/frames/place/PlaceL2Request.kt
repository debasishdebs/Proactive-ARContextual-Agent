package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity

/** One image of a Layer 2 call: Base64 JPEG and the photo ref the router re-checks (a Forgotten photo drops the call unsent). */
class L2Image(val base64: String, val ref: String)

/** One candidate of a Layer 2 call: the place and the reference photos (already capped per candidate) that may go with it. A place with no photo has an empty list and is still sent, as text. */
class L2Candidate(val place: PlaceEntity, val refs: List<L2Image> = emptyList())

/** [lines] go in the prompt's data block; [images] = the camera photo FIRST, then the reference photos in the order the lines name them. */
class L2Request(val lines: List<String>, val images: List<L2Image>, val refCount: Int, val withRefs: Int)

/**
 * Layer 2 (online vision model) request, owner spec 28 Nov: the camera photo AND the reference photos of the candidates AND each candidate's name, kind and description go to the model in ONE call, which decides
 * which numbered candidate (or none) the camera photo shows. Pure: no Android, no I/O. Nothing here is logged; the caller logs counts only.
 */
object PlaceL2Request {
    /** At most this many reference photos per candidate, and in total per call (plus the camera photo). */
    const val PER_CANDIDATE = 2
    const val MAX_REFS = 8
    private const val DESC_MAX = 240

    fun build(camera: L2Image, candidates: List<L2Candidate>, perCandidate: Int = PER_CANDIDATE, maxRefs: Int = MAX_REFS): L2Request {
        val images = ArrayList<L2Image>().apply { add(camera) }
        val lines = ArrayList<String>()
        lines += "Image 1 = the camera photo to identify. Which numbered candidate, if any, does the camera photo show? Use the reference photos and the text. Answer none if unsure."
        var withRefs = 0
        candidates.forEachIndexed { i, c ->
            val room = (maxRefs - (images.size - 1)).coerceAtLeast(0)
            val take = c.refs.take(minOf(perCandidate, room))
            val nums = take.map { images.size + 1 + take.indexOf(it) }
            take.forEach { images.add(it) }
            if (take.isNotEmpty()) withRefs++
            val desc = c.place.summary.trim().replace('\n', ' ').take(DESC_MAX)
            val text = listOf(c.place.name, c.place.kind.replace('_', ' ')).plus(if (desc.isEmpty()) emptyList() else listOf(desc)).joinToString(" | ")
            val photos = if (take.isEmpty()) "no reference photo" else "reference photo" + (if (take.size > 1) "s" else "") + " " + nums.joinToString(", ") { "Image $it" }
            lines += "${i + 1}. $text | $photos"
        }
        return L2Request(lines, images, images.size - 1, withRefs)
    }
}
