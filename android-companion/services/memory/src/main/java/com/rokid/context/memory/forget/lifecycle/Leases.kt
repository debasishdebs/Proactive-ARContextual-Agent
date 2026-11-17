package com.rokid.context.memory.forget.lifecycle

import androidx.room.withTransaction
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX

/**
 * The read-lease registry of the Forget lifecycle (design 2026-11-16, section 2.3). A flow that reads stored user data and later writes something derived from it (an Ask, a Nudge, a canvas run, a derive,
 * a canvas Save, a re-summarise) holds a [Lease] on the refs it read and COMMITS through it. A Forget [revoke]s every open lease whose inputs it reaches (a flag: a model call is never waited for or cancelled
 * here), and a revoked lease's [Lease.commit] writes nothing. Admission (new leases wait while a Forget holds the exclusive side) is the job of the `DataOpGate`; this registry has no gate and no suspension.
 *
 * Why it is race free: SQLite has one writer at a time. A commit that ran before the revocation is in the database (the Forget's own plan reads it); one that runs after sees the flag and writes nothing.
 * The existing fences (wipe generation, tombstones, [ForgetFence]) stay as the second line, checked in the same transaction. Ids only; nothing here ever holds text.
 */
class Leases {
    enum class Kind { ASK, NUDGE, CANVAS_RUN, DERIVE, CANVAS_SAVE, RESUMMARIZE, REANSWER }

    private val lock = Any()
    private val open = LinkedHashSet<Lease>()
    private var epoch = 0L
    /** The last revocations (epoch, refs; null = everything): a lease that [Lease.extend]s late must still meet a Forget that ran after it opened. */
    private val history = ArrayDeque<Pair<Long, Set<Ref>?>>()

    /** How many leases are open (tests, and a log count). */
    val openCount: Int get() = synchronized(lock) { open.size }

    /** Registers a lease. Never suspends and never refuses: admission is the gate's. [currentGeneration] is the live wipe generation ([generation] is the one read before the flow's reads). */
    fun open(kind: Kind, inputs: Collection<Ref>, generation: Long, currentGeneration: () -> Long = { generation }): Lease = synchronized(lock) {
        Lease(this, kind, inputs.toHashSet(), generation, currentGeneration, epoch).also { open += it }
    }

    /** Revokes every open lease whose inputs meet [refs]. Returns how many. */
    fun revoke(refs: Collection<Ref>): Int = synchronized(lock) {
        val set = refs.toHashSet()
        record(set)
        open.count { l -> (l.inputs.any { it in set }).also { hit -> if (hit) l.revoked = true } }
    }

    /** Revokes every open lease whose inputs the [plan] reaches (the executor calls it after its in-transaction re-plan). Returns how many. */
    fun revoke(plan: ForgetPlan): Int = revoke(plan.nodes.keys)

    /** Revokes every open lease (Delete all, an unplanned Forget: fail closed). */
    fun revokeAll(): Int = synchronized(lock) {
        record(null)
        open.onEach { it.revoked = true }.size
    }

    private fun record(refs: Set<Ref>?) {
        epoch++
        history.addLast(epoch to refs)
        while (history.size > HISTORY) history.removeFirst()
    }

    /** A late input: revoked at once when a Forget since the lease opened reached it (or when the history no longer reaches back that far). */
    private fun extend(l: Lease, more: Collection<Ref>) = synchronized(lock) {
        l.inputs += more
        if (l.revoked) return@synchronized
        val since = history.filter { it.first > l.openEpoch }
        val lost = history.firstOrNull()?.first?.let { it > l.openEpoch + 1 } ?: false
        if (lost || since.any { (_, refs) -> refs == null || more.any { it in refs } }) l.revoked = true
    }

    private fun close(l: Lease) { synchronized(lock) { open -= l } }

    /** What [Lease.commit] returns: the block ran and committed, or nothing was written. */
    sealed interface Commit<out T> {
        data class Committed<T>(val value: T) : Commit<T>
        /** The lease was revoked or a fence closed: the block did not run, nothing was written. */
        data object Fenced : Commit<Nothing>
    }

    class Lease internal constructor(
        private val owner: Leases, val kind: Kind, internal val inputs: MutableSet<Ref>, val generation: Long, private val currentGeneration: () -> Long, internal val openEpoch: Long,
    ) {
        @Volatile internal var revoked = false
        val isRevoked: Boolean get() = revoked
        /** A copy of the refs read so far. */
        fun inputs(): Set<Ref> = synchronized(owner.lock) { inputs.toSet() }

        /** Adds inputs read late (after the lease opened). A Forget that already reached them revokes the lease. */
        fun extend(more: Collection<Ref>) { if (more.isNotEmpty()) owner.extend(this, more) }

        /** Ends the lease (always, in a `finally`). Idempotent. */
        fun close() = owner.close(this)

        /**
         * True while this lease may still write: not revoked, no Delete all since it opened, and (when [checkLive]) every photo input is not tombstoned, every memory input exists and is not INVALIDATED and
         * every mark input exists. Call it INSIDE the writing transaction ([commit] does).
         */
        suspend fun verify(db: ContextDatabase, checkLive: Boolean = true): Boolean = verify(db.canvasDao(), checkLive)

        /** As above, over the [CanvasDao] alone (the canvas run's own transaction has no database handle). */
        suspend fun verify(dao: CanvasDao, checkLive: Boolean = true): Boolean {
            if (revoked || currentGeneration() != generation) return false
            if (!checkLive) return true
            val all = inputs()
            val photos = all.filter { it.family == Family.PHOTO }.map { it.id }
            if (photos.isNotEmpty() && dao.tombstonedAmong(photos) > 0) { revoked = true; return false }
            val memories = all.filter { it.family == Family.MEMORY }.map { it.id }
            if (memories.isNotEmpty() && dao.liveMemoryCount(memories) != memories.toSet().size) { revoked = true; return false }
            val marks = all.filter { it.family == Family.MARK }.map { it.id }
            if (marks.isNotEmpty() && dao.labelsMissing(marks) > 0) { revoked = true; return false }
            return !revoked
        }

        /** Runs [block] in ONE Room transaction if [verify] still holds there, else writes nothing ([Commit.Fenced]). The transaction takes the write lock before [verify] reads. */
        suspend fun <T> commit(db: ContextDatabase, checkLive: Boolean = true, block: suspend () -> T): Commit<T> = db.withTransaction {
            if (!verify(db, checkLive)) Commit.Fenced else Commit.Committed(block())
        }
    }

    companion object {
        private const val HISTORY = 32
        /** The one registry of the process (the `DataOpGate.shared` gate uses it). */
        val shared = Leases()

        /** Refs of stored ids as flows carry them: `frame:<id>` is a photo cite, any other id a memory. */
        fun refsOf(ids: Collection<String>): List<Ref> = ids.map { if (it.startsWith(PHOTO_CITE_PREFIX)) Ref(Family.PHOTO, it.removePrefix(PHOTO_CITE_PREFIX)) else Ref(Family.MEMORY, it) }
        fun photos(frameIds: Collection<String>): List<Ref> = frameIds.map { Ref(Family.PHOTO, it) }
        fun memories(ids: Collection<String>): List<Ref> = ids.map { Ref(Family.MEMORY, it) }
        fun marks(ids: Collection<String>): List<Ref> = ids.map { Ref(Family.MARK, it) }
    }
}
