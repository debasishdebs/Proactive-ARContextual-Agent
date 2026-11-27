package com.rokid.context.work.frames.place

import com.rokid.context.core.log.PcLog

/**
 * Developer only: ONE canned, generic place card ("Send test card to glasses", Settings > Diagnostics > Developer), to try the glasses wake-up (CardWaker / WakeLimiter) by hand.
 * It is published into the SAME [PlaceCardHolder] a real place card uses, so it travels the real path (Hub.placeOverlay, the `Overlay.Nudge` + `PlaceCardInfo` wire, the glasses' card pop-up and
 * wake, the glasses' dismissal) and is withdrawn like any card (Forget, Delete all, expiry). It carries nothing from the owner's data and writes NOTHING: no place_card_log row, no history, no
 * link, no Moment, no preference. The only state is the in-memory time of the last send (the 10 s limit). Log lines are the two literal tokens below, never content.
 * Not reachable from the dump, import or canvas APIs (a source rule pins who may name this class).
 */
class DevTestCardSender(
    private val holder: PlaceCardHolder,
    private val clock: () -> Long,
    private val developerMode: () -> Boolean,
    private val connected: () -> Boolean,
) {
    enum class Outcome { SENT, REFUSED_DEVELOPER_OFF, REFUSED_NOT_CONNECTED, REFUSED_RATE }

    private var lastSentAt: Long? = null

    @Synchronized fun send(): Outcome {
        val out = when {
            !developerMode() -> Outcome.REFUSED_DEVELOPER_OFF
            !connected() -> Outcome.REFUSED_NOT_CONNECTED
            lastSentAt?.let { clock() - it in 0 until MIN_INTERVAL_MS } == true -> Outcome.REFUSED_RATE
            else -> Outcome.SENT
        }
        if (out == Outcome.SENT) {
            lastSentAt = clock()
            holder.publish(DevTestCard.CARD)
            PcLog.i(TAG, "dev test card sent")
        } else PcLog.i(TAG, "dev test card refused") // a refused tap does not start the interval
        return out
    }

    companion object {
        const val TAG = "PcDevTest"
        /** At most one test card per this long (ms). */
        const val MIN_INTERVAL_MS = 10_000L
    }
}

/** The canned card and what the Developer row shows. Fixed text only. */
object DevTestCard {
    const val TITLE = "Test card"
    const val LINE = "Glasses wake test"
    const val SOURCE = "Developer test"
    const val PLACE_ID = "dev_test_card"
    const val FRAME_ID = "dev_test_card" // matches no frame: no Forget of a photo can touch it, Delete all and the glasses' dismissal clear it

    /** Looks like a nearby card (no photo, no pin), so nothing on the glasses or the phone treats it as the owner's place. */
    val CARD = PlaceCard(PLACE_ID, TITLE, LINE, SOURCE, PlaceConfidence.NEARBY, FRAME_ID)

    const val NOT_CONNECTED_NOTE = "Connect the glasses first: the test card is sent over the glasses link."
    const val IDLE_NOTE = "Sends one generic card (no place, no data of yours) to the glasses, like a real place card. Not saved anywhere. At most one every 10 seconds; the glasses wake at most once a minute."

    data class Row(val visible: Boolean, val enabled: Boolean, val note: String)

    fun row(developerMode: Boolean, connected: Boolean): Row =
        if (!developerMode) Row(false, false, "") else Row(true, connected, if (connected) IDLE_NOTE else NOT_CONNECTED_NOTE)

    /** The one line shown after a tap (fixed text, no content). */
    fun message(o: DevTestCardSender.Outcome): String = when (o) {
        DevTestCardSender.Outcome.SENT -> "Sent. Look at the glasses."
        DevTestCardSender.Outcome.REFUSED_DEVELOPER_OFF -> "Turn Developer mode on first."
        DevTestCardSender.Outcome.REFUSED_NOT_CONNECTED -> NOT_CONNECTED_NOTE
        DevTestCardSender.Outcome.REFUSED_RATE -> "Wait 10 seconds between test cards."
    }
}
