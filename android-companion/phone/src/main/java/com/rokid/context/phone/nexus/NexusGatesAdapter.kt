package com.rokid.context.phone.nexus

import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneTicket
import io.github.pca.nexusport.NexusGates
import io.github.pca.nexusport.NexusPass
import kotlinx.coroutines.CancellationException

/**
 * [NexusGates] over PCA's EXISTING fences, nothing new: the provenance gate (sources live), the Place Guide zone fence, Ask's zone fence, the pause state and the wear state. Every gate that throws
 * answers "blocked" here (fail closed), so the sink (which also treats a throw as a block) can never be fooled by a gate that errors: sources not live, fences held, paused, not worn.
 * Cancellation is not an error and passes through.
 */
class PcaNexusGates(
    private val sourcesLive: suspend (Set<String>) -> Boolean,
    private val zoneFence: () -> Boolean,
    private val askFence: () -> Boolean,
    private val paused: () -> Boolean,
    private val worn: () -> Boolean,
    /** The real fences as one pass ([fencePass]); null = the sink's default (re-read both flags, check-then-act with a gap: tests only). */
    private val passFactory: (() -> NexusPass)? = null,
) : NexusGates {
    override fun pass(): NexusPass = passFactory?.invoke() ?: super.pass()
    override suspend fun sourcesLive(ids: Set<String>): Boolean = try { sourcesLive.invoke(ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
    override fun zoneFenceBlocked(): Boolean = try { zoneFence() } catch (_: Exception) { true }
    override fun askFenceBlocked(): Boolean = try { askFence() } catch (_: Exception) { true }
    override fun paused(): Boolean = try { paused.invoke() } catch (_: Exception) { true }
    override fun worn(): Boolean = try { worn.invoke() } catch (_: Exception) { false }
}

/**
 * Place Guide's pattern (`executeFenced`) over BOTH real fences: the tickets are taken when the pass is (before the delivery's first suspension), and [NexusPass.act] runs the delivery under the Place Guide fence's
 * lock and then Ask's, with the zone read as the last step before it. A hold or trip that landed after the tickets closed them (the delivery is dropped); one landing during the delivery waits for its lock, then the
 * sink's trip withdraws what was drawn. Lock order is always place fence, then ask fence; neither fence calls out while holding its lock.
 * [askTicket] null = Ask's zone fence switch is off (no ticket, only [askBlocked] is read).
 */
fun fencePass(place: ZoneFence, askTicket: () -> ZoneTicket?, askBlocked: () -> Boolean): NexusPass {
    val pt = place.ticket()
    val at = askTicket()
    return object : NexusPass {
        override fun <T : Any> act(block: () -> T): T? {
            var out: T? = null
            place.act(pt) {
                if (!askBlocked() && !place.blocked()) out = if (at != null) at.fence.act(at, block) else block()
                true
            }
            return out
        }
    }
}
