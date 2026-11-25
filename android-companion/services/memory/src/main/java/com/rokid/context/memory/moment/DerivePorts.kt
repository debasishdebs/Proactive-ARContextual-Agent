package com.rokid.context.memory.moment

/** Why a derived field is rebuilt: the latest build, part or forget operation read from the object's audit (op name and reference only). */
data class RebuildReason(val op: String?, val refKind: String?, val refId: String?)

/** What a port is asked: ANY [MomentObject], the field, and the reason. */
/** [fence] is the deletion generation and epoch and [inputSeq] the input revision, both captured BEFORE the port runs and re-checked when its answer is written. */
data class DeriveRequest(val obj: MomentObject, val field: String, val reason: RebuildReason, val fence: com.rokid.context.memory.db.DaoFence, val inputSeq: Int)

/** What a port answers: one derived-field write ([value] for SUMMARY and CAPTION, [ref] for EMBEDDING). */
data class DerivedWrite(
    val field: String, val value: String? = null, val ref: String? = null, val builtRev: Int,
    /** v18 WP11a: the model that wrote [value] (null = not known: nothing is recorded). The name only, never text. */
    val model: String? = null,
)

/** Generic ports of the processing functions. The real adapters (model calls) come later; each takes any Moment-family object and returns a derived-field write or null (nothing to write). */
fun interface SummarizeInput { suspend fun summarize(req: DeriveRequest): DerivedWrite? }
fun interface CaptionInput { suspend fun caption(req: DeriveRequest): DerivedWrite? }
fun interface EmbedInput { suspend fun embed(req: DeriveRequest): DerivedWrite? }

/** The ports by objectKind, so Moment, Audio, Sensor and Scene all use the same call sites. A kind with no port for a field has none: [DeriveRunner] then writes nothing. */
class DeriveRegistry {
    private class Ports(val summarize: SummarizeInput?, val caption: CaptionInput?, val embed: EmbedInput?)
    private val byKind = HashMap<String, Ports>()

    @Synchronized fun register(kind: String, summarize: SummarizeInput? = null, caption: CaptionInput? = null, embed: EmbedInput? = null) { byKind[kind] = Ports(summarize, caption, embed) }
    @Synchronized fun summarizer(kind: String): SummarizeInput? = byKind[kind]?.summarize
    @Synchronized fun captioner(kind: String): CaptionInput? = byKind[kind]?.caption
    @Synchronized fun embedder(kind: String): EmbedInput? = byKind[kind]?.embed

    companion object {
        /** No-op ports for a kind: every call answers null (nothing is written). For wiring and tests only; there is no model behind it. */
        fun noOp(vararg kinds: String): DeriveRegistry = DeriveRegistry().also { r -> kinds.forEach { r.register(it, SummarizeInput { null }, CaptionInput { null }, EmbedInput { null }) } }
    }
}

/** Rebuilds derived fields through the registry. Reads the reason from the audit, calls the port of the object's kind and writes the answer with [ObjectAudit.writeDerived]. */
class DeriveRunner(private val audit: ObjectAudit, private val registry: DeriveRegistry, private val fence: () -> com.rokid.context.memory.db.DaoFence) {
    /** The database the lease commit runs its transaction on (the DerivePass sets it; without it a lease is not used). */
    var db: com.rokid.context.memory.db.ContextDatabase? = null
    /** True when a port answered and its write was stored. A result that is obsolete when it arrives (object deleted, Delete all, inputs changed) is dropped: see [rebuildOutcome]. */
    suspend fun rebuild(obj: MomentObject, field: String): Boolean = rebuildOutcome(obj, field) == com.rokid.context.memory.db.ObjectDao.WriteOutcome.WRITTEN

    /** [WriteOutcome.WRITTEN] when stored, the rejection when the answer was obsolete, null when no port answered. */
    suspend fun rebuildOutcome(obj: MomentObject, field: String, lease: com.rokid.context.memory.forget.lifecycle.Leases.Lease? = null): com.rokid.context.memory.db.ObjectDao.WriteOutcome? {
        val captured = fence()
        val req = DeriveRequest(obj, field, audit.reason(obj), captured, audit.inputSeq(obj))
        val answer = when (field) {
            DerivedField.SUMMARY -> registry.summarizer(obj.kind)?.summarize(req)
            DerivedField.CAPTION -> registry.captioner(obj.kind)?.caption(req)
            DerivedField.EMBEDDING -> registry.embedder(obj.kind)?.embed(req)
            else -> null
        } ?: return null
        if (answer.field != field) return null // a port writes only the field it was asked for
        // Forget lifecycle M3: with a read [lease] (the DerivePass holds one over the object and what its text was read from) the write commits THROUGH it: a Forget that reached those inputs while the model
        // ran revoked it and nothing is written (reported as a stale input, like any obsolete answer). The input-revision guard of the write stays as the second line.
        val db = db
        if (lease == null || db == null) return audit.writeDerivedGuarded(obj, field, answer.value, answer.ref, answer.builtRev, req.fence, req.inputSeq, com.rokid.context.memory.db.ModelAttribution.of(answer.model))
        return when (val c = lease.commit(db, checkLive = false) { audit.writeDerivedGuarded(obj, field, answer.value, answer.ref, answer.builtRev, req.fence, req.inputSeq, com.rokid.context.memory.db.ModelAttribution.of(answer.model)) }) {
            is com.rokid.context.memory.forget.lifecycle.Leases.Commit.Committed -> c.value
            com.rokid.context.memory.forget.lifecycle.Leases.Commit.Fenced -> com.rokid.context.memory.db.ObjectDao.WriteOutcome.STALE_INPUT
        }
    }

    /** Rebuilds every field that is absent or stale (hidden). Returns the number rebuilt. */
    suspend fun rebuildMissingOrStale(obj: MomentObject): Int = DerivedField.ALL.count { f -> audit.readDerived(obj, f) == null && rebuild(obj, f) }
}
