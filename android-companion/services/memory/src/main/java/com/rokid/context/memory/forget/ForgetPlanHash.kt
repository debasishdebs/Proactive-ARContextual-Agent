package com.rokid.context.memory.forget

import java.security.MessageDigest

/** A Forget confirmed against a different set than the one previewed is refused (the typed outcome `CONTENT_CHANGED` above this layer): new photos, marks or Moments in the span, or a changed Scene membership. Nothing was deleted. */
class ForgetContentChanged : Exception("content changed since the preview")

/** The lineage closure of a Moment/Scene Forget rebuilds more than [MomentForget.MAX_UNACKED_RESULTS] regenerable results and the confirm token did not carry the acknowledgement (the preview's `ackHash`). Nothing was deleted. */
class ForgetNeedsAck : Exception("more regenerable results than the unacknowledged limit")

/** The facts connected to a Forget go deeper or wider than one step can safely handle (depth or node cap): nothing was deleted (typed outcome `TOO_LARGE`; use a smaller scope or Delete all). */
class ForgetTooLarge : Exception("too much is connected to forget in one step")

/** The hash of what a scoped Forget would remove: ids and spans only, canonical (sorted), so the preview and the delete agree on exactly one set. */
internal object ForgetPlanHash {
    fun of(effective: ForgetScope, spans: List<LongRange>, sections: Map<String, Collection<String>>): String {
        val text = StringBuilder("scope=${effective.name};spans=").append(spans.sortedBy { it.first }.joinToString(",") { "${it.first}-${it.last}" })
        sections.toSortedMap().forEach { (k, v) -> text.append(';').append(k).append('=').append(v.toSortedSet().joinToString(",")) }
        return MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
