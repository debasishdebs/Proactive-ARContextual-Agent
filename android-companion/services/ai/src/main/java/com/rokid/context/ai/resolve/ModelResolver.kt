package com.rokid.context.ai.resolve

import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.BackendDecision
import com.rokid.context.core.net.BackendPick
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetState
import com.rokid.context.core.net.ResolveReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** What a consumer asks for. The role maps to a binding per backend; [PLACE_EMBED] is reserved for slice 1.4.4 (no factory is registered yet, so it resolves as not bound). */
enum class ResolverRole { PLACE_CONFIRM, PLACE_EMBED }

/**
 * How one (role, backend) pair is made. [bound] and [fingerprint] must be cheap and never load a model; [create] is the expensive part and runs at most once per fingerprint (single-flight, cached).
 * [label] is a fixed, content-free word for the log (for example `remote:AZURE_OPENAI` or `device:LLAMACPP_LOCAL`): never an endpoint name, address or a user-typed model string.
 */
interface BackendFactory {
    suspend fun bound(): Boolean
    /** Changes whenever the instance would be a different one (endpoint, address, model, model file): the cache entry is then rebuilt. */
    suspend fun fingerprint(): String
    suspend fun label(): String
    suspend fun create(): Any
    /** An evicted instance (a local model) is released here. Default: nothing to release. */
    fun close(instance: Any) {}
}

sealed interface Resolved {
    val role: ResolverRole
    data class Ready(override val role: ResolverRole, val backend: Backend, val instance: Any, val label: String, val cacheHit: Boolean, val loadMs: Long, val mode: ModelPrivacy) : Resolved
    /** Typed "no backend": the caller fails closed (or keeps today's no-call behaviour for AUTO without a network). [reason] is the fixed token that is logged. */
    data class Unavailable(override val role: ResolverRole, val reason: ResolveReason, val mode: ModelPrivacy) : Resolved
}

/**
 * ONE place that picks the backend for a role: the SETTING first, then the NETWORK ([BackendDecision]), caches one instance per (role, backend), and logs which backend ran and why. It sits in
 * front of the router, not instead of it: an ONLINE instance is still called through `ModelRouter`, which independently refuses remote endpoints under OFFLINE_ONLY. Under OFFLINE_ONLY this class
 * never returns ONLINE, whatever [net] says; the OFFLINE slot is a typed [Resolved.Unavailable] until a factory is registered (slice 1.4.5).
 *
 * Log line (one per resolution, only fixed tokens and enum names): `resolve role=PLACE_CONFIRM mode=AUTO net=VALIDATED backend=ONLINE label=remote:AZURE_OPENAI cache=hit loadMs=0 reason=ok`.
 */
class ModelResolver(
    private val mode: () -> ModelPrivacy,
    private val net: () -> NetState,
    private val factories: Map<Pair<ResolverRole, Backend>, BackendFactory>,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fingerprint: String, val instance: Any, val label: String)
    private val cache = ConcurrentHashMap<Pair<ResolverRole, Backend>, Entry>()
    private val locks = ConcurrentHashMap<Pair<ResolverRole, Backend>, Mutex>()

    /** The mode as read now. An unreadable setting counts as OFFLINE_ONLY (fail closed). */
    private fun modeNow(): ModelPrivacy = try { mode() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }
    /** The network as read now. A failing network check is [NetState.UNKNOWN]. */
    private fun netNow(): NetState = try { net() } catch (e: Exception) { NetState.UNKNOWN }

    /** The decision alone: nothing is created or cached and nothing is logged (for the status line). */
    suspend fun peek(role: ResolverRole): BackendPick {
        val m = modeNow()
        return BackendDecision.decide(m, netNow(), onlineAvailable = isBound(role, Backend.ONLINE), offlineAvailable = isBound(role, Backend.OFFLINE))
    }

    suspend fun resolve(role: ResolverRole): Resolved {
        val m = modeNow(); val n = netNow()
        val pick = BackendDecision.decide(m, n, onlineAvailable = isBound(role, Backend.ONLINE), offlineAvailable = isBound(role, Backend.OFFLINE))
        val backend = pick.backend ?: return unavailable(role, m, n, pick.reason)
        val key = role to backend
        val factory = factories[key] ?: return unavailable(role, m, n, ResolveReason.NOT_BOUND)
        val started = nowMs()
        return try {
            locks.computeIfAbsent(key) { Mutex() }.withLock {
                val fp = factory.fingerprint()
                val cached = cache[key]
                if (cached != null && cached.fingerprint == fp) {
                    ready(role, backend, cached, hit = true, loadMs = 0L, m, n)
                } else {
                    cached?.let { evict(key, factory, it) }
                    val made = Entry(fp, factory.create(), factory.label())
                    cache[key] = made
                    ready(role, backend, made, hit = false, loadMs = (nowMs() - started).coerceAtLeast(0L), m, n)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            val why = ResolveReason.LOAD_FAILED.token
            log("resolve role=${role.name} mode=${m.name} net=${n.name} backend=${backend.name} label=- cache=miss loadMs=-1 reason=$why err=${e.javaClass.simpleName}")
            Resolved.Unavailable(role, ResolveReason.LOAD_FAILED, m)
        }
    }

    /** Drops the cached instance(s) of [role] (all roles when null) and releases them: a binding, endpoint or model file changed, or the user asked. */
    suspend fun invalidate(role: ResolverRole? = null) {
        for ((key, factory) in factories) {
            if (role != null && key.first != role) continue
            locks.computeIfAbsent(key) { Mutex() }.withLock { cache[key]?.let { evict(key, factory, it) } }
        }
    }

    private fun evict(key: Pair<ResolverRole, Backend>, factory: BackendFactory, e: Entry) {
        cache.remove(key, e)
        try { factory.close(e.instance) } catch (t: Exception) { /* a failed release never blocks the rebuild */ }
    }

    private suspend fun isBound(role: ResolverRole, b: Backend): Boolean = try { factories[role to b]?.bound() == true } catch (c: CancellationException) { throw c } catch (e: Exception) { false }

    private fun ready(role: ResolverRole, b: Backend, e: Entry, hit: Boolean, loadMs: Long, m: ModelPrivacy, n: NetState): Resolved {
        val cache = if (hit) "hit" else "miss"
        val why = ResolveReason.OK.token
        log("resolve role=${role.name} mode=${m.name} net=${n.name} backend=${b.name} label=${e.label} cache=$cache loadMs=$loadMs reason=$why")
        return Resolved.Ready(role, b, e.instance, e.label, hit, loadMs, m)
    }

    private fun unavailable(role: ResolverRole, m: ModelPrivacy, n: NetState, reason: ResolveReason): Resolved {
        val why = reason.token
        log("resolve role=${role.name} mode=${m.name} net=${n.name} backend=none label=- cache=- loadMs=-1 reason=$why")
        return Resolved.Unavailable(role, reason, m)
    }
}
