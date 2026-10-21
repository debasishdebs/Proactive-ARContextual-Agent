package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState

/**
 * NOT1 (R11): decides when the phone posts its one silent "Open on glasses" notification. Pure: feed every link state change and a tick every 5 s.
 * The notice shows once [LinkState.CLOSED_ON_GLASSES] has held for [DELAY_MS] without a break (the relauncher's first reopen is at 8 s, so a
 * successful automatic reopen cancels it first), and at most once per closed episode: after it showed, was dismissed or was used, there is no second
 * [Action.Show] until the state has left CLOSED_ON_GLASSES and come back. Any other state answers [Action.Cancel] (idempotent, and it also clears a
 * notice a dead process left posted). State lives in memory only: nothing here is stored (no backup rule).
 */
class ClosedOnGlassesNotice(private val clock: () -> Long) {
    sealed interface Action { data object None : Action; data object Show : Action; data object Cancel : Action }

    private var closedSince: Long? = null
    private var episodeDone = false

    fun onState(state: LinkState): Action {
        if (state != LinkState.CLOSED_ON_GLASSES) { closedSince = null; episodeDone = false; return Action.Cancel }
        if (closedSince == null) closedSince = clock()
        return onTick()
    }

    fun onTick(): Action {
        val since = closedSince ?: return Action.None
        if (episodeDone || clock() - since < DELAY_MS) return Action.None
        episodeDone = true
        return Action.Show
    }

    /** The user swiped the notification away: this episode is over. */
    fun onDismissed() { episodeDone = true }

    /** The user tapped "Open on glasses": this episode is over. */
    fun onOpened() { episodeDone = true }

    companion object { const val DELAY_MS = 45_000L }
}
