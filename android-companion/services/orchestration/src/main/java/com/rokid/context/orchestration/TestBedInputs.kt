package com.rokid.context.orchestration

import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.ModelImageInput
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars

/** spec §4.5 test bed inputs: a recent frame, free text, or a canned question (ANSWER/NUDGE use real retrieval). */
sealed interface TestInput {
    data class Frame(val frameId: String) : TestInput
    data class Text(val text: String) : TestInput
    data class Question(val text: String) : TestInput
}

/** Builds the same delimited variables production uses, so a test run sees what the pipeline would send. */
class TestBedInputs(private val memory: MemoryGraph, private val answers: AnswerService) {
    data class Prepared(val vars: PromptVars, val images: List<ModelImageInput>, val inputIds: Set<String>)

    suspend fun recentFrames(limit: Int = 12): List<FrameEntity> = memory.frames.recent(limit)

    suspend fun prepare(key: PromptKey, input: TestInput): Prepared {
        val text = when (input) { is TestInput.Text -> input.text; is TestInput.Question -> input.text; is TestInput.Frame -> "" }
        return when (key) {
            PromptKey.SYSTEM -> Prepared(PromptVars(), emptyList(), emptySet())
            PromptKey.ANSWER -> {
                val r = answers.retrieve(text.ifBlank { "What did I see today?" }, System.currentTimeMillis())
                Prepared(r.vars, emptyList(), r.ids)
            }
            PromptKey.NUDGE -> {
                val recent = memory.memories.recent(30).filter { it.reviewState == ReviewState.OK }.take(10)
                val lines = recent.map { "id=${it.id} · ${AskText.clip(AskText.collapse(it.text), 300)}" } + listOfNotNull(text.takeIf { it.isNotBlank() })
                Prepared(PromptVars(recentContext = PromptComposer.dataBlock("memories", lines), contextSources = PromptComposer.dataBlock("sources", emptyList())),
                    emptyList(), recent.map { it.id }.toSet())
            }
            PromptKey.VISION_EXTRACT -> {
                val frame = frameFor(input)
                val lines = listOfNotNull(frame?.let(::frameLine), text.takeIf { it.isNotBlank() })
                Prepared(PromptVars(recentContext = PromptComposer.dataBlock("frames", lines)), frame?.let { FrameInputs.fromFrames(listOf(it), 1) }.orEmpty(), emptySet())
            }
            PromptKey.EXPLICIT_REMEMBER -> Prepared(PromptVars(recentContext = PromptComposer.dataBlock("user_said", listOf(text))), emptyList(), emptySet())
            PromptKey.SUMMARIZE -> Prepared(
                PromptVars(recentContext = PromptComposer.dataBlock("memories", text.lines().filter { it.isNotBlank() }) + "\n" + PromptComposer.dataBlock("critic_notes", listOf("test run"))),
                emptyList(), emptySet(),
            )
            PromptKey.CRITIC -> {
                // Same two blocks as QueueDrainWorker: the facts, then the frame they came from (none for typed facts).
                val frame = frameFor(input)
                val facts = PromptComposer.dataBlock("facts", text.lines().filter { it.isNotBlank() })
                Prepared(PromptVars(recentContext = facts + "\n" + PromptComposer.dataBlock("source", listOfNotNull(frame?.let(::frameLine)))), emptyList(), emptySet())
            }
        }
    }

    private suspend fun frameFor(input: TestInput): FrameEntity? =
        (input as? TestInput.Frame)?.let { f -> memory.frames.byIds(listOf(f.frameId)).firstOrNull() }

    private fun frameLine(f: FrameEntity) = "id=${f.id} · ts=${f.ts} · source=${f.source} · ${f.payload}"
}
