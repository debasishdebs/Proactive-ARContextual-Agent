package com.rokid.context.ai

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.SendOnce
import com.rokid.context.core.net.Skipped
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.PassThroughGate
import com.rokid.context.model.PayloadSources
import com.rokid.context.model.PhotoLiveness
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What a routed call returned, and from where (the test bed shows latency + tokens; RoleRunner logs the endpoint).
 * [truncated]: stopped at the token cap; [maxTokens]: the cap that call sent (request, else binding; null = provider default).
 */
data class RoutedReply(
    val text: String, val endpoint: Endpoint, val model: String, val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?,
    val truncated: Boolean = false, val maxTokens: Int? = null,
    /**
     * Codex r10 delivery proof: true only when this reply came from the provider call that carried the request's photos (the endpoint has VISION, the
     * provider [com.rokid.context.model.ModelProvider.acceptsImages], the liveness re-check passed). A text-only reply is false. In memory only; a save
     * that persists a photo description requires it.
     */
    val imagesDelivered: Boolean = false,
)

/**
 * Routes a role's call to its bound endpoint. Vision is used only when the endpoint can deliver images and images are given;
 * a vision failure retries as text on the same endpoint unless the request requires its images; every photo upload passes the image-dispatch
 * guard in [call]; a primary failure retries on the fallback binding. [build] is
 * called once per endpoint tried, so each kind gets its own wire format. The binding's temperature/maxTokens fill
 * whatever the request leaves null. Failures surface as [ModelCallException] with a [FailureKind]; cancellation is
 * rethrown untouched.
 */
class ModelRouter(
    private val bindings: RoleBindingRepository,
    private val endpoints: EndpointRepository,
    private val providers: ProviderBuilder,
    /** Every individual provider call (fallbacks and retries too) runs inside this, with the caller's [CallClass]. */
    private val gate: CallGate = PassThroughGate,
    /** The roaming data saver (slice 1). Asked once per provider call, before the permit; the default allows everything (tests, tools that never see the network). */
    private val dataGate: DataGate = DataGate.ALLOW_ALL,
    /**
     * Model privacy (slice 1.4.3), read at EVERY provider call (never cached). [ModelPrivacy.OFFLINE_ONLY] refuses every endpoint that is not on this phone or on the user's own network, on every entry
     * point and for the primary, the fallback and the SUMMARIZE hop alike. A reader that throws counts as OFFLINE_ONLY (fail closed). The default (tests, tools) is [ModelPrivacy.AUTO].
     */
    private val privacy: () -> ModelPrivacy = { ModelPrivacy.AUTO },
) : LanguageModel {
    /**
     * Fail-closed privacy check for [e], asked BEFORE its key is read and before any provider is built or request made. Throws [OfflineOnlyRefusedException] (a park-without-attempt block) when the mode is
     * OFFLINE_ONLY and the endpoint is [EndpointReach.REMOTE]. The fixed log token carries no host, endpoint or content.
     */
    private fun requireAllowedByPrivacy(e: Endpoint) = OfflineOnlyGuard.require(privacy, e)

    /** [liveness]: required with [images] (codex r10; the router refuses a photo without it). */
    @Deprecated("Pass a CallClass", ReplaceWith("generate(role, CallClass.OTHER, prompt, images)"))
    suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList(), liveness: PhotoLiveness? = null): String =
        generate(role, CallClass.OTHER, prompt, images, liveness)

    suspend fun generate(role: Role, callClass: CallClass, prompt: String, images: List<ModelImageInput> = emptyList(), liveness: PhotoLiveness? = null): String =
        complete(role, callClass) { ModelRequest(prompt, images, liveness = liveness, sources = PayloadSources.NONE) }.text // legacy: images carry their refs

    @Deprecated("Pass a CallClass", ReplaceWith("generateFromFrames(CallClass.OTHER, prompt, images)"))
    suspend fun generateFromFrames(prompt: String, images: List<ModelImageInput>, liveness: PhotoLiveness? = null): String =
        generateFromFrames(CallClass.OTHER, prompt, images, liveness)

    suspend fun generateFromFrames(callClass: CallClass, prompt: String, images: List<ModelImageInput>, liveness: PhotoLiveness? = null): String =
        completeFromFrames(callClass) { ModelRequest(prompt, images, liveness = liveness, sources = PayloadSources.NONE) }.text // legacy: images carry their refs

    @Deprecated("Pass a CallClass", ReplaceWith("complete(role, CallClass.OTHER, build)"))
    suspend fun complete(role: Role, build: (Endpoint) -> ModelRequest): RoutedReply = complete(role, CallClass.OTHER, build)

    override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val b = bindings.get(role) ?: throw ModelCallException("No model is set for ${role.name}", retryable = true, kind = FailureKind.NOT_SET)
        return counting { route(b, build, skipPrimary = false, callClass) }
    }

    /** Counts a data-saver block ONCE per outer call (the primary and the fallback, or the vision and summarize chain, may each be refused for the same call). */
    private suspend fun <T> counting(block: suspend () -> T): T = try { block() } catch (d: DataSaverBlockedException) {
        if (!d.counted) { d.counted = true; dataGate.skipped(d.dataKind, d.what, DataDecision(false, d.reason)) }
        throw d
    }

    /**
     * Frame summarisation, matching the legacy worker: vision → text on the vision endpoint, then SUMMARIZE's chain.
     * When SUMMARIZE shares the vision endpoint+model, only its fallback is tried (the primary already failed as text).
     */
    @Deprecated("Pass a CallClass", ReplaceWith("completeFromFrames(CallClass.OTHER, build)"))
    suspend fun completeFromFrames(build: (Endpoint) -> ModelRequest): RoutedReply = completeFromFrames(CallClass.OTHER, build)

    override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val vision = bindings.get(Role.VISION_EXTRACT) ?: return complete(Role.SUMMARIZE, callClass, build)
        return counting { completeFromFramesChain(vision, callClass, build) }
    }

    private suspend fun completeFromFramesChain(vision: RoleBinding, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        return try {
            route(vision, build, skipPrimary = false, callClass)
        } catch (first: ModelCallException) {
            if (first is PhotoForgottenException || first is DataSaverBlockedException) throw first   // codex r10: a forgotten photo is never retried, not on SUMMARIZE's chain either
            val summarize = bindings.get(Role.SUMMARIZE) ?: throw first
            val samePrimary = summarize.endpointId == vision.endpointId && summarize.model == vision.model
            if (samePrimary && summarize.fallbackEndpointId == null) throw first
            try {
                route(summarize, build, skipPrimary = samePrimary, callClass)
            } catch (second: ModelCallException) {
                if (second is PhotoForgottenException || second is DataSaverBlockedException) throw second
                // A photo-only call whose SUMMARIZE endpoint cannot take the photo: the vision failure is the real one (a 400 stays a 400, keeps its attempts and parking).
                if (needsVision(second)) throw first
                throw ModelCallException("Frame summary failed: ${first.message}; ${second.message}", first.retryable || second.retryable, second, ProviderErrors.worst(first.kind, second.kind))
            }
        }
    }

    /** Test bed: exactly this endpoint and model, no binding params and no fallback. */
    @Deprecated("Pass a CallClass", ReplaceWith("completeOn(endpointId, model, CallClass.OTHER, build)"))
    suspend fun completeOn(endpointId: String, model: String, build: (Endpoint) -> ModelRequest): RoutedReply =
        completeOn(endpointId, model, CallClass.OTHER, build)

    override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply {
        val e = endpoints.byId(endpointId) ?: throw ModelCallException("That endpoint was deleted", retryable = false, kind = FailureKind.NOT_SET)
        // P8 D3: the key is read once, before the call; the failure path never reads it again, so a Keystore failure
        // there can't replace the provider's error.
        var key: String? = null
        return try {
            requireAllowedByPrivacy(e) // before the key is read
            key = endpoints.keyFor(e)
            counting { call(e, model, null, build, key, callClass) }
        } catch (c: CancellationException) {
            throw c
        } catch (m: ModelCallException) {
            throw m
        } catch (t: Throwable) {
            throw ModelCallException("Test call failed: ${safe(t, key)}", !ProviderErrors.isPermanent(t), t, ProviderErrors.kindOf(t))
        }
    }

    private suspend fun route(b: RoleBinding, build: (Endpoint) -> ModelRequest, skipPrimary: Boolean, callClass: CallClass): RoutedReply {
        val primaryEndpoint = if (skipPrimary) null else
            endpoints.byId(b.endpointId) ?: throw ModelCallException("Endpoint for ${b.role.name} was deleted", retryable = true, kind = FailureKind.NOT_SET)
        // P8 D3: each key is read once, before its call; a failure path never reads it again (see completeOn).
        var primaryKey: String? = null
        val first: Throwable? = primaryEndpoint?.let { primary ->
            try {
                requireAllowedByPrivacy(primary) // before the key is read
                primaryKey = endpoints.keyFor(primary)
                return call(primary, b.model, b, build, primaryKey, callClass)
            } catch (c: CancellationException) { throw c } catch (f: PhotoForgottenException) { throw f } catch (u: ImagesUnguardedException) { throw u } catch (t: Throwable) { t }
        }
        val fallback = b.fallbackEndpointId?.let { endpoints.byId(it) }
            ?: throw first as? DataSaverBlockedException   // the data saver blocked the primary and there is no fallback: report that, not a generic failure
            ?: ModelCallException(
                "Primary provider failed: ${safe(first, primaryKey)}",
                retryable = first == null || !ProviderErrors.isPermanent(first), first,
                kind = first?.let(ProviderErrors::kindOf) ?: FailureKind.NOT_SET,
            )
        var fallbackKey: String? = null
        return try {
            requireAllowedByPrivacy(fallback) // the fallback is held to the same rule as the primary: a remote one is refused under Offline only
            fallbackKey = endpoints.keyFor(fallback)
            call(fallback, b.fallbackModel ?: b.model, b, build, fallbackKey, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (f: PhotoForgottenException) {
            throw f
        } catch (d: DataSaverBlockedException) {
            throw d
        } catch (t: Throwable) {
            // The data saver blocked the primary and the on-device/LAN fallback failed too: the call is still "blocked, nothing sent, wait for Wi-Fi", never a counted failure.
            // Offline only refused the primary and the on-device/LAN fallback WAS tried but failed: say that (the fallback's own failure), not that the primary was refused. (A fallback that cannot take the photo keeps the refusal below.)
            if (first is OfflineOnlyRefusedException && !needsVision(t)) {
                if (!ProviderErrors.isPermanent(t)) { // a box that is asleep or unreachable right now: park without an attempt (like the refusal itself), never burn the retry budget overnight
                    val kind = ProviderErrors.kindOf(t)
                    PcLog.i(TAG, "offline_only_fallback_unavailable kind=${kind.name}")
                    throw OfflineOnlyRefusedException(fallbackKind = kind)
                }
                throw ModelCallException("Offline only: the on-device or LAN fallback failed: ${safe(t, fallbackKey)}", !ProviderErrors.isPermanent(t), t, ProviderErrors.kindOf(t))
            }
            if (first is DataSaverBlockedException) throw first
            if (first != null && needsVision(t)) {
                // A photo-only call whose fallback cannot take the photo: report the primary's own failure, not "not set".
                throw ModelCallException("Primary provider failed: ${safe(first, primaryKey, fallbackKey)}", !ProviderErrors.isPermanent(first), first, ProviderErrors.kindOf(first))
            }
            val retryable = !ProviderErrors.isPermanent(t) || (first != null && !ProviderErrors.isPermanent(first))
            throw ModelCallException(
                "Primary+fallback generation failed: ${safe(first, primaryKey, fallbackKey)}; ${safe(t, primaryKey, fallbackKey)}",
                retryable, t, ProviderErrors.worst(first?.let(ProviderErrors::kindOf), ProviderErrors.kindOf(t)),
            )
        }
    }

    /** Type-based config check (permanent): never depends on a provider's error wording. */
    private fun requireConfigured(e: Endpoint, model: String, key: String?) {
        val problem = when {
            e.baseUrl.isBlank() -> if (e.kind == EndpointKind.LLAMACPP_LOCAL) "No model file set for ${e.name}" else "No address set for ${e.name}"
            e.kind != EndpointKind.LLAMACPP_LOCAL && model.isBlank() -> "No model set for ${e.name}"
            e.kind == EndpointKind.AZURE_OPENAI && key.isNullOrBlank() -> "No API key saved for ${e.name}"
            else -> null
        }
        if (problem != null) throw ModelCallException(problem, retryable = false, kind = FailureKind.NOT_SET)
    }

    /**
     * THE image-dispatch choke point (codex r10). Every provider call of every model call runs here. For a request that is about stored photos (image refs
     * or [ModelRequest.sources]: photos and source memories):
     *  1. every image must carry a ref and the request a [com.rokid.context.model.PhotoLiveness], else [ImagesUnguardedException] (nothing sent);
     *  2. a [ModelRequest.requireImages] call whose endpoint or provider cannot deliver images fails closed: [ImagesUnsupportedException], no output;
     *  3. inside the call permit: the liveness' upload slot is taken, every photo id re-checked, and the slot held until the provider call returns;
     *     a photo gone meanwhile drops the call unsent: [PhotoForgottenException], which no path below or above retries;
     *  4. a failed image call is retried as text only when the request does not require its images (and the retry re-checks liveness too).
     * [RoutedReply.imagesDelivered] is set only for a reply whose call carried the photos.
     */
    private suspend fun call(e: Endpoint, model: String, b: RoleBinding?, build: (Endpoint) -> ModelRequest, key: String?, callClass: CallClass): RoutedReply {
        requireAllowedByPrivacy(e) // the last guard (every provider call passes here, whichever entry point or retry led to it)
        requireConfigured(e, model, key)
        val p = providers.create(e, model, key)
        val request = build(e).let { r -> r.copy(temperature = r.temperature ?: b?.temperature, maxTokens = r.maxTokens ?: b?.maxTokens) }
        val photoIds = request.sources.frameIds + request.images.mapNotNull { it.ref }
        val memoryIds = request.sources.memoryIds
        val markIds = request.sources.markIds
        val liveness = request.liveness
        // codex r14 RULE C: every request carrying stored content (any sources but NONE, or a photo) is guarded, even with empty id sets
        val guarded = request.sources.stored || photoIds.isNotEmpty() || memoryIds.isNotEmpty() || markIds.isNotEmpty()
        if (request.images.any { it.ref.isNullOrBlank() } || (guarded && liveness == null)) {
            PcLog.w(TAG, "image_dispatch_refused reason=unguarded")
            throw ImagesUnguardedException()
        }
        val canSend = request.images.isNotEmpty() && EndpointRules.deliversImages(e) && p.acceptsImages
        if (request.requireImages && !canSend) {
            PcLog.i(TAG, "vision_refused_no_text_retry images_unsupported")
            throw ImagesUnsupportedException()
        }
        // Roaming data saver (slice 1): a pure pre-check BEFORE any permit, upload slot or provider call, so a blocked call spends no budget token and never reaches the liveness logic.
        // It is not retried as text (a photo call that is blocked stays a photo call), and nothing is sent. The fallback is only tried when it is on-device or on the LAN (its own check passes).
        val dataKind = if (canSend) DataKind.PHOTO else DataKind.SMALL
        val sendOnce = callClass == CallClass.USER && kotlin.coroutines.coroutineContext[SendOnce.Key] != null
        var dataDecision = dataGate.decide(dataKind, reachOf(e))
        // Slice 2 "Send once": a USER-class photo call the user confirmed on the phone (the SendOnce element is put on the coroutine by the screen's own tap) is asked again as "once". Nothing else can carry it.
        if (!dataDecision.allowed && canSend && callClass == CallClass.USER && kotlin.coroutines.coroutineContext[SendOnce.Key] != null) dataDecision = dataGate.decideOnce(dataKind, reachOf(e))
        if (!dataDecision.allowed) throw DataSaverBlockedException(dataDecision.reason, dataKind, if (canSend) Skipped.UPLOAD_QUEUED else Skipped.BULK) // counted once by [counting]
        // Each provider call (the vision attempt and its text retry are two) takes its own permit, then the upload slot, then the liveness re-check.
        val start = System.nanoTime()
        // Slice 2 byte counter, at this single choke point: payload only (the request's prompt and Base64 photos as sent, the reply text), counts only.
        // The network class is read in send(), once per provider call, at the moment the call holds its permit (and upload slot): the permit wait can be long, and the class that counts is the one the request goes out on
        // (the vision attempt and its text retry each read their own). A call that ends on another network is still counted where it began.
        // Everything but a model on the phone itself is a candidate: an on-device model sends nothing, and a loopback endpoint (localhost, 127.x, ::1, e.g. Ollama in Termux) never leaves the phone. A loopback endpoint keeps the LAN reach for the data-saver gate (unchanged), it is only not counted. A LAN endpoint is NOT free by its address: DataPolicy treats it as free only on Wi-Fi/Ethernet, and on mobile data a LAN host
        // (192.168.x or 10.x through WireGuard/Tailscale) is allowed and its bytes DO use mobile data. So [costlyNow] at the permit decides, and the ledger counts only while it is true.
        // A failed call does NOT use up the photo cap: photos count on success only. The request bytes of a failure count only when the server demonstrably received the request and answered:
        // an HTTP error status, or a 2xx whose reply could not be used ([ProviderResponseException]: missing or empty content, malformed JSON; the providers map their parse failures to it).
        // Never counted: a connect, DNS, TLS, timeout or other IO failure, nor a read timeout after the request was sent (the request may well have been received, so the byte cap can be slightly permissive).
        suspend fun send(r: ModelRequest): com.rokid.context.model.ModelReply {
            val photos = r.images.size
            val kind = if (photos > 0) DataKind.PHOTO else DataKind.SMALL // the text-only retry of a photo call is a text call
            val tx = DataBytes.requestBytes(r)
            // Codex data-saver r1 P1-1: the admission above can be minutes old (the permit wait, the liveness slot): the policy, the caps and the CURRENT network are asked again right here, immediately before the
            // request leaves. A photo admitted on Wi-Fi that now sits on roaming is dropped unsent (and, below, a call that is already running is aborted when the network turns costly under it).
            val again = if (reachOf(e) == EndpointReach.DEVICE) DataDecision.ALLOW else decideNow(e, kind, photos, callClass, sendOnce) // an on-device model is never gated
            if (!again.allowed) throw DataSaverBlockedException(again.reason, kind, if (photos > 0) Skipped.UPLOAD_QUEUED else Skipped.BULK)
            val counted = reachOf(e) != EndpointReach.DEVICE && !EndpointReaches.isLoopback(e)
            val costlyAtStart = counted && dataGate.costlyNow()
            // P1-6: an admission RESERVATION is counted (persisted) before a byte leaves: whatever happens next (a stall after a complete upload, a timeout, a cancel, process death) the usage is not zero.
            val reservation = if (costlyAtStart) safeReserve(kind, tx + DataBytes.ENVELOPE, photos) else null
            fun report(rxBytes: Long, countedPhotos: Int, countedTx: Long = tx) {
                if (reservation != null) safeSettle(reservation, countedTx, rxBytes, countedPhotos)
                else if (counted && (countedTx > 0 || rxBytes > 0)) safeSent(kind, countedTx, rxBytes, countedPhotos, costlyAtStart)
            }
            val reply = try { if (counted) watchingNetwork(e, kind, photos, callClass, sendOnce) { p.complete(r) } else p.complete(r) }
            catch (t: Throwable) {
                // Only a failure that proves nothing left (DNS, connect, TLS handshake, config) releases the reservation. An HTTP error or an unusable 2xx counts the request (never a photo). Anything else (a timeout or
                // stall after the upload, a cancel, our own abort, an unknown error) keeps the conservative count: the request, and its photos, may well have been received.
                when {
                    TrafficClass.provablyUnsent(t) -> report(0, 0, countedTx = 0)
                    TrafficClass.receivedAnswer(t) -> report(0, 0)
                    else -> report(0, photos)
                }
                throw t
            }
            // outside the try above: a throwing ledger can neither count the call twice nor discard a paid reply
            report(DataBytes.replyBytes(reply), photos)
            return reply
        }
        suspend fun gated(r: ModelRequest) = gate.withPermit(callClass) {
            if (liveness == null || !guarded) send(r) else liveness.holdUploadSlot {
                val gone = liveness.gone(photoIds) // also the wipe generation the liveness captured: checked even when no id is attached
                val goneMemories = if (memoryIds.isEmpty()) emptySet() else liveness.goneMemories(memoryIds)
                val goneMarks = if (markIds.isEmpty()) emptySet() else liveness.goneMarks(markIds)
                if (!gone.none || goneMemories.isNotEmpty() || goneMarks.isNotEmpty()) {
                    if (gone.wiped) PcLog.i(TAG, "image_dispatch_dropped reason=wiped photos=${gone.ids.size}")
                    else PcLog.i(TAG, "image_dispatch_dropped reason=forgotten photos=${gone.ids.size} memories=${goneMemories.size} marks=${goneMarks.size}")
                    throw PhotoForgottenException(gone.ids, gone.wiped, goneMemories, goneMarks)
                }
                send(r)
            }
        }
        var delivered = false
        val reply = if (canSend) {
            try { gated(request).also { delivered = true } } catch (c: CancellationException) { throw c } catch (f: PhotoForgottenException) { throw f }
            catch (d: DataSaverBlockedException) { throw d } // the saver closed the gate right before the request (or under it): a photo call stays a photo call, never a text retry
            catch (t: Throwable) {
                if (request.requireImages) {   // a text-only retry would guess without the photo (codex r9 P1-2)
                    PcLog.w(TAG, "vision_refused_no_text_retry ${SafeLog.describe(t)}")
                    throw t
                }
                PcLog.w(TAG, "vision failed, retrying as text: ${SafeLog.describe(t)}")
                gated(request.copy(images = emptyList()))
            }
        } else {
            gated(request.copy(images = emptyList()))
        }
        return RoutedReply(reply.text, e, model, (System.nanoTime() - start) / 1_000_000, reply.promptTokens, reply.completionTokens, reply.truncated, request.maxTokens, imagesDelivered = delivered)
    }

    /** On this phone (never gated), a box on the user's own network (free on Wi-Fi), or anything else. Reads the address only to classify it; the host is never logged. */
    /** The counter must never break a call: a throwing ledger is logged (fixed token) and dropped. */
    private fun safeSent(kind: DataKind, tx: Long, rx: Long, photos: Int, costlyAtStart: Boolean) {
        try { dataGate.sentAt(kind, tx, rx, photos, costlyAtStart) } catch (c: CancellationException) { throw c } catch (t: Throwable) { PcLog.w(TAG, "data counter failed: ${t.javaClass.simpleName}") }
    }

    /** The policy for one request right now. A USER-class photo call the user sent "once" is asked again as "once" (same rule as the admission). */
    private fun decideNow(e: Endpoint, kind: DataKind, photos: Int, callClass: CallClass, sendOnce: Boolean): DataDecision {
        val reach = reachOf(e)
        val d = dataGate.decide(kind, reach)
        return if (!d.allowed && photos > 0 && callClass == CallClass.USER && sendOnce) dataGate.decideOnce(kind, reach) else d
    }

    /**
     * Runs one provider call and, while it runs, asks the policy about the CURRENT network every [NET_WATCH_MS]. When the network turns costly and this call is not allowed there, the call is cancelled and fails with
     * [DataSaverBlockedException] (the request may have been partly sent: the caller keeps the conservative reservation). Best effort: the socket is not bound to the admitted network, so bytes already on the wire stay counted.
     */
    private suspend fun <T> watchingNetwork(e: Endpoint, kind: DataKind, photos: Int, callClass: CallClass, sendOnce: Boolean, block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        val abort = java.util.concurrent.atomic.AtomicReference<DataDecision?>(null)
        val work = async { block() }
        val watch = launch {
            while (true) {
                delay(NET_WATCH_MS)
                val d = try { decideNow(e, kind, photos, callClass, sendOnce) } catch (c: CancellationException) { throw c } catch (t: Throwable) { DataDecision(false, DataPolicy.R_UNKNOWN) } // an unreadable state fails closed
                if (!d.allowed) { abort.set(d); work.cancel(); return@launch }
            }
        }
        try { work.await() }
        catch (c: CancellationException) {
            val d = abort.get()
            if (d != null && isActive) { PcLog.i(TAG, "request aborted: network became costly kind=${kind.name}"); throw DataSaverBlockedException(d.reason, kind, if (photos > 0) Skipped.UPLOAD_QUEUED else Skipped.BULK) }
            throw c
        } finally { watch.cancel() }
    }

    private fun safeReserve(kind: DataKind, tx: Long, photos: Int): com.rokid.context.core.net.DataReservation? =
        try { dataGate.reserve(kind, tx, photos) } catch (c: CancellationException) { throw c } catch (t: Throwable) { PcLog.w(TAG, "data counter failed: ${t.javaClass.simpleName}"); null }

    private fun safeSettle(r: com.rokid.context.core.net.DataReservation, tx: Long, rx: Long, photos: Int) {
        try { dataGate.settle(r, tx, rx, photos) } catch (c: CancellationException) { throw c } catch (t: Throwable) { PcLog.w(TAG, "data counter failed: ${t.javaClass.simpleName}") } // the reservation stays counted
    }

    private fun reachOf(e: Endpoint): EndpointReach = EndpointReaches.of(e)

    internal companion object {
        const val TAG = "ModelRouter"
        const val MAX_PROVIDER_TEXT = 200
        /** How often a running provider call re-asks the data saver about the current network. */
        const val NET_WATCH_MS = 1_000L
        const val NEEDS_VISION = "This call needs a vision endpoint and its photo"
        /** True when [t] failed only because a photo-only request met an endpoint that cannot deliver images (also when [route] wrapped that one failure). */
        fun needsVision(t: Throwable?): Boolean = t is ModelCallException && t.kind == FailureKind.NOT_SET &&
            generateSequence<Throwable>(t) { it.cause?.takeIf { c -> c !== it } }.take(3).any { it is ImagesUnsupportedException }
        /** Fixed log token: a photo call failed (or had no vision endpoint) and was NOT retried as text. */
        const val VISION_REFUSED_NO_TEXT_RETRY = "vision_refused_no_text_retry"

        /**
         * Failure text for exception messages. Only our own fixed texts are kept ([ModelCallException], whose message is
         * built here from these parts, and [ProviderConfigException]); a provider throwable becomes class, kind and HTTP
         * code ([SafeLog.describe]), so no provider body (echoed request text, memory, keys) ever enters a message.
         * Known [keys] are still masked, each also in its URL-encoded and Base64 forms (P8 D3), then the text is truncated.
         */
        fun safe(t: Throwable?, vararg keys: String?): String {
            var m = when (t) {
                null -> ""
                is ModelCallException, is ProviderConfigException -> t.message.orEmpty()
                else -> SafeLog.describe(t)
            }
            keys.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.flatMap(::maskForms).distinct()
                .sortedByDescending { it.length }.forEach { m = m.replace(it, "***") }
            return if (m.length <= MAX_PROVIDER_TEXT) m else m.take(MAX_PROVIDER_TEXT) + "…"
        }

        /** The key, its URL-encoded form and its Base64 form (padded or not, e.g. inside a Basic-auth header). */
        private fun maskForms(key: String): List<String> = listOf(
            key,
            java.net.URLEncoder.encode(key, "UTF-8"),
            java.util.Base64.getEncoder().encodeToString(key.toByteArray(Charsets.UTF_8)).trimEnd('='),
        ).filter { it.isNotEmpty() }
    }
}

/** Where an endpoint's calls go, for the data saver: on this phone, a box on the user's own network (free on Wi-Fi), or anything else. Reads the address only to classify it; the host is never logged. */
object EndpointReaches {
    /**
     * Where a PHOTO call can really go: the best reach among the endpoints that can deliver the photo ([EndpointRules.deliversImages]: VISION declared, not the on-device text runtime). A remote primary with an on-device or LAN
     * TEXT fallback is still REMOTE, because the fallback cannot take the photo (the router rethrows the primary's refusal). When none of them can, the first (the primary's) reach decides, as before. Empty = REMOTE (fail closed).
     */
    fun photoReach(endpointsInOrder: List<Endpoint>): EndpointReach {
        val capable = endpointsInOrder.filter(EndpointRules::deliversImages).map(::of)
        return capable.minOrNull() ?: endpointsInOrder.firstOrNull()?.let(::of) ?: EndpointReach.REMOTE
    }

    /** An endpoint on this phone itself (localhost, 127.x, ::1): its traffic never touches the network, so it is not counted as mobile data. Its gate reach stays [of] (LAN). */
    fun isLoopback(e: Endpoint): Boolean = e.kind != EndpointKind.LLAMACPP_LOCAL && DataPolicy.isLoopbackHost(runCatching { java.net.URI(e.baseUrl.trim()).host }.getOrNull())

    fun of(e: Endpoint): EndpointReach = when {
        e.kind == EndpointKind.LLAMACPP_LOCAL -> EndpointReach.DEVICE
        DataPolicy.isLanHost(runCatching { java.net.URI(e.baseUrl.trim()).host }.getOrNull()) -> EndpointReach.LAN
        else -> EndpointReach.REMOTE
    }
}

/** Sizes for the byte counter: what the request body and the reply carry as text, never the content itself. */
internal object DataBytes {
    /** About the wire size of a model request: the prompt in UTF-8, each photo as the Base64 string that is sent, and a fixed 512 B for the JSON envelope (TLS and headers are not counted). */
    fun requestBytes(r: ModelRequest): Long = ENVELOPE + r.prompt.toByteArray(Charsets.UTF_8).size + r.images.sumOf { it.base64Data.length.toLong() }
    fun replyBytes(r: com.rokid.context.model.ModelReply): Long = ENVELOPE + r.text.toByteArray(Charsets.UTF_8).size
    const val ENVELOPE = 512L
}

/** How a failed provider call is counted by the byte ledger (shared by the router, the embedding client and Endpoint Test). */
internal object TrafficClass {
    /** The failure proves no request body left the phone: name resolution, connect, TLS handshake, a refused cleartext address, or our own config error. */
    fun provablyUnsent(t: Throwable): Boolean = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(6).any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
            it is javax.net.ssl.SSLHandshakeException || it is java.net.UnknownServiceException || it is ProviderConfigException
    } && !(t is java.net.SocketTimeoutException)

    /** The server demonstrably received the request and answered: an HTTP error status, or a 2xx whose reply could not be used. The request counts, a photo does not. */
    fun receivedAnswer(t: Throwable): Boolean = t is ProviderHttpException || t is ProviderResponseException || t is kotlinx.serialization.SerializationException
}
