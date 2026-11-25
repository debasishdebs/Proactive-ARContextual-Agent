package com.rokid.context.canvas.live

import com.rokid.context.core.net.ModelPrivacy
import java.util.concurrent.atomic.AtomicReference

/**
 * Canvas v2.1 P4 (design section 1.5), the PURE part of the bounded live tap: every limit and every privacy stop, checked before each frame. The session driver (`runItems` refactor of `CanvasRunner`, the
 * committed-frame flow) is NOT built here: it touches the runner's fenced commit and needs the glasses (see the decisions file, "device check pending"). The driver must call [beforeFrame] first and stop at
 * once on a non-null answer; it must call [photoMaySend] before building any image request and [photoSent] after.
 *
 * Safest defaults: duration 5 min (max 15), frames 50, photos sent 0 (offline only), nominal calls 200. Anything unreadable (privacy mode, zone state) ends the session (fail closed).
 */
enum class TapStop(val token: String) {
    DURATION("tap_duration"), FRAMES("tap_frames"), CALLS("tap_calls"), PHOTO_CAP("tap_photo_cap"),
    PRIVACY_FLIP("tap_privacy_flip"), ZONE("tap_zone"), HUB_STOPPED("tap_hub_stopped"), PAGE_CLOSED("tap_page_closed"),
    DELETE_ALL("tap_delete_all"), FORGET("tap_forget"), USER("tap_user_stop"), UNREADABLE("tap_unreadable"),
}

class LiveTapBounds(
    private val startedAtMs: Long,
    private val maxMin: Int,
    private val maxFrames: Int,
    private val maxPhotosSent: Int,
    private val maxCalls: Int = 200,
    private val startPrivacy: ModelPrivacy,
    private val nowMs: () -> Long,
) {
    init {
        require(maxMin in 1..MAX_MIN) { "bad_bounds" }
        require(maxFrames in 1..MAX_FRAMES) { "bad_bounds" }
        require(maxPhotosSent in 0..maxFrames) { "bad_bounds" }
        require(maxCalls in 1..MAX_CALLS) { "bad_bounds" }
    }

    private var frames = 0
    private var calls = 0
    private var photos = 0
    private var lastBeat = startedAtMs
    private val latched = AtomicReference<TapStop?>(null)

    /** The first reason ever recorded stays: a later signal never replaces it. */
    private fun latch(s: TapStop) { latched.compareAndSet(null, s) }

    fun stopped(): TapStop? = latched.get()

    // signals from the rest of the app (any thread)
    fun onDeleteAll() = latch(TapStop.DELETE_ALL)
    fun onForgetOverlap() = latch(TapStop.FORGET)
    fun onHubStopped() = latch(TapStop.HUB_STOPPED)
    fun onUserStop() = latch(TapStop.USER)
    fun onZoneEntered() = latch(TapStop.ZONE)
    fun heartbeat() { lastBeat = nowMs() }

    /**
     * Checked before each frame. [privacy] is the mode read now (null = unreadable: fail closed); [zoneClear] is null when the zone state cannot be read (fail closed), false inside a pause zone.
     * Returns the reason to stop, or null to continue with this frame (it is counted).
     */
    fun beforeFrame(privacy: ModelPrivacy?, zoneClear: Boolean?, nominalCallsForFrame: Int): TapStop? {
        latched.get()?.let { return it }
        if (privacy == null || zoneClear == null) { latch(TapStop.UNREADABLE); return stopped() }
        if (privacy != startPrivacy) { latch(TapStop.PRIVACY_FLIP); return stopped() }
        if (!zoneClear) { latch(TapStop.ZONE); return stopped() }
        val now = nowMs()
        if (now - lastBeat > HEARTBEAT_MS) { latch(TapStop.PAGE_CLOSED); return stopped() }
        if (now - startedAtMs >= maxMin * 60_000L) { latch(TapStop.DURATION); return stopped() }
        if (frames >= maxFrames) { latch(TapStop.FRAMES); return stopped() }
        if (calls + nominalCallsForFrame > maxCalls) { latch(TapStop.CALLS); return stopped() }
        frames++; calls += nominalCallsForFrame
        return null
    }

    /** True when one more photo may leave the phone. Under OFFLINE_ONLY it is never true; a spent budget ends the session. */
    fun photoMaySend(): Boolean {
        if (latched.get() != null || startPrivacy == ModelPrivacy.OFFLINE_ONLY) return false
        if (photos >= maxPhotosSent) { latch(TapStop.PHOTO_CAP); return false }
        return true
    }
    fun photoSent() { photos++ }

    val framesSeen: Int get() = frames
    val photosSent: Int get() = photos

    companion object {
        const val MAX_MIN = 15
        const val MAX_FRAMES = 50
        const val MAX_CALLS = 200
        const val HEARTBEAT_MS = 30_000L
        const val DEFAULT_MIN = 5
    }
}
