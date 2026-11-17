package com.rokid.context.memory.forget.lifecycle

/**
 * What a Forget would do, as nodes with a fate (design 2.2). Ids only. In SHADOW mode (M2) this is compared with what the existing code really deletes; nothing here deletes anything.
 * Rows that a Forget redacts in place (an ask, a nudge, a proposal, a hidden derived field, an invalidated memory) are REBUILD in this v1-parity plan; the v2 rules (A1, N1) change that at M5.
 */
class ForgetPlan(val nodes: Map<Ref, Fate>, val revisions: Map<Ref, String> = emptyMap()) {
    fun refs(family: Family, fate: Fate? = null): Set<String> = nodes.filter { it.key.family == family && (fate == null || it.value == fate) }.map { it.key.id }.toSet()
    /** Counts per family: what a confirm dialog or a log line may show. */
    val counts: Map<Family, Int> get() = nodes.keys.groupingBy { it.family }.eachCount()
    val size: Int get() = nodes.size
    /** One line per node, sorted: `memory:mA ERASE`. Stable text for tests. */
    fun render(): String = nodes.entries.map { "${it.key} ${it.value}" }.sorted().joinToString("\n")
    /**
     * [render] PLUS a fingerprint of the content of every affected row ([revisions]: a stable hash, never a text), so a token confirms the content that was previewed and not only its ids: a memory updated in place under the
     * same id changes the text. Hashed by the multi-select and the `forgetV2` Moment / Scene tokens; the delete recomputes it in its own transaction (preview == delete).
     */
    fun renderWithContent(): String = nodes.entries.map { "${it.key} ${it.value} ${revisions[it.key].orEmpty()}" }.sorted().joinToString("\n")
}

/** The rule set of a plan: which existing Forget path it mirrors (v1 parity). */
sealed interface Policy {
    /** A user Forget of a fact, a photo or a mark (`ForgetService.forgetFacts` / `forgetMoment`, the mark undo). */
    data object User : Policy
    /** Retention expiry of memories (`ForgetService.expire`): no REBUILD of descendants, accepted proposals kept, keyframes kept. */
    data object Retention : Policy
    /** Retention expiry of photos (`ForgetService.expirePhotos`). */
    data class RetentionPhotos(val requireLinked: Boolean = false, val recheckSkipped: Boolean = false) : Policy
    /**
     * A Forget of whole seconds ([spans], already rounded outward): the range Forget, Forget of Moment ids ([byKey] false) and the Moment / Scene scope ([byKey] true, with [sceneIds] going whole).
     * [requestedSeconds] is empty for a span too long to tombstone second by second.
     */
    data class Spans(val spans: List<LongRange>, val requestedSeconds: List<Long> = emptyList(), val sceneIds: List<String> = emptyList(), val byKey: Boolean = false) : Policy
}
