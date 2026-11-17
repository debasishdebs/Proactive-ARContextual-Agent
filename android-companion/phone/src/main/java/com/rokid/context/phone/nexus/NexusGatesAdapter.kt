package com.rokid.context.phone.nexus

import io.github.pca.nexusport.NexusGates
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
) : NexusGates {
    override suspend fun sourcesLive(ids: Set<String>): Boolean = try { sourcesLive.invoke(ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
    override fun zoneFenceBlocked(): Boolean = try { zoneFence() } catch (_: Exception) { true }
    override fun askFenceBlocked(): Boolean = try { askFence() } catch (_: Exception) { true }
    override fun paused(): Boolean = try { paused.invoke() } catch (_: Exception) { true }
    override fun worn(): Boolean = try { worn.invoke() } catch (_: Exception) { false }
}
