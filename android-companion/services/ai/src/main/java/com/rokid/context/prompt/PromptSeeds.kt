package com.rokid.context.prompt

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.contract.PromptKey

/** First-launch prompt text per key (spec §4.3 seeds). */
fun interface PromptSeeds { fun template(key: PromptKey): String }

/** Minimal valid seeds, used in tests and when an asset is missing. */
object BuiltInPromptSeeds : PromptSeeds {
    override fun template(key: PromptKey): String = when (key) {
        PromptKey.SYSTEM -> "You are Proactive Context, a private memory assistant. Persona: {{persona}}. Reply in {{language}}. Now: {{now}}."
        PromptKey.VISION_EXTRACT -> "Extract short facts from the frames.\n{{recent_context}}"
        PromptKey.CLUSTER_SUMMARY -> "Summarise each scene from its photos.\n{{recent_context}}"
        PromptKey.INTENT_CLASSIFY -> "Classify what the wearer is doing from this photo. Reply only with JSON."
        PromptKey.EXPLICIT_REMEMBER -> "Turn what the user said into facts.\n{{recent_context}}"
        PromptKey.SUMMARIZE -> "Re-summarise these memories.\n{{recent_context}}"
        PromptKey.CRITIC -> "Check the facts against the source.\n{{recent_context}}"
        PromptKey.ANSWER -> "Answer the question from the memories.\nQuestion:\n{{question}}\nMemories:\n{{retrieved_memories}}\nOther context:\n{{context_sources}}"
        PromptKey.NUDGE -> "Decide whether a short heads-up helps now.\n{{recent_context}}\n{{context_sources}}"
    }
}

/** Reads `assets/prompts/seeds/<file>` (shipped by `:services:ai`); falls back to [BuiltInPromptSeeds]. */
class AssetPromptSeeds(context: Context, private val dir: String = "prompts/seeds") : PromptSeeds {
    private val assets = context.applicationContext.assets

    override fun template(key: PromptKey): String =
        runCatching { assets.open("$dir/${file(key)}").bufferedReader().use { it.readText() } }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: BuiltInPromptSeeds.template(key).also { PcLog.w(TAG, "Seed asset missing for ${key.name}; using the built-in seed") }

    companion object {
        private const val TAG = "AssetPromptSeeds"

        fun file(key: PromptKey): String = when (key) {
            PromptKey.SYSTEM -> "system_global.txt"
            PromptKey.VISION_EXTRACT -> "write_context_batched.txt"
            PromptKey.CLUSTER_SUMMARY -> "cluster_summary.txt"
            PromptKey.INTENT_CLASSIFY -> "intent_classify.txt"
            PromptKey.EXPLICIT_REMEMBER -> "write_context_explicit.txt"
            PromptKey.SUMMARIZE -> "re_summarize.txt"
            PromptKey.CRITIC -> "critic_validation.txt"
            PromptKey.ANSWER -> "read_context_answer.txt"
            PromptKey.NUDGE -> "nudge.txt"
        }
    }
}
