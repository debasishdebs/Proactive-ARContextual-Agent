package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.AskSourceStates
import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Ask again" (Forget lifecycle M5, user ruling 2026-11-17): the user's own tap on an answer whose source was forgotten. There is NO automatic re-answer and nothing is scheduled: this runs the stored
 * QUESTION (the user's own words, kept by the Forget) through the normal Ask path ([AskActions.askFrom]: Battery Saver, the router, the leases, the provenance gate, a fresh retrieval over what is live now),
 * which saves a NEW answer row. The old row is only marked "asked again" so it shows no second button. Never runs for a developer eval ask or for an answer that is not in the forgotten-source state.
 */
object ReanswerWorker {
    private const val TAG = "ReanswerWorker"

    /** The question of [askId] when it may be asked again, else null. Reads the row only. */
    suspend fun askableQuestion(context: Context, askId: String): String? =
        MemoryGraph.get(context).asks.byId(askId)?.takeIf { it.source != AskSourceStates.EVAL_SOURCE && AskSourceStates.parse(it.sourceStates)?.state == AskSourceStates.SOURCE_FORGOTTEN }?.question?.takeIf { it.isNotBlank() }

    /**
     * Asks the stored question again as a typed ask. [answerThrough] is the surface's own typed-ask call (the phone adds its HUD mirror); the default is the plain [AskActions.ask]. Returns the outcome;
     * on success the old row is marked as asked again. A failure changes nothing (the button stays).
     */
    suspend fun reask(context: Context, askId: String, answerThrough: suspend (String) -> AskOutcome = { AskActions.ask(context, it) }): AskOutcome {
        val question = askableQuestion(context, askId) ?: return AskOutcome.Failed(AskActions.FAILED)
        val outcome = try { answerThrough(question) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "ask again failed (${e.javaClass.simpleName})"); return AskOutcome.Failed(AskActions.FAILED) }
        if (outcome is AskOutcome.Answered) runCatching { MemoryGraph.get(context).asks.markReasked(askId, outcome.askId) }.onFailure { PcLog.w(TAG, "asked-again mark not saved (${it.javaClass.simpleName})") }
        return outcome
    }
}
