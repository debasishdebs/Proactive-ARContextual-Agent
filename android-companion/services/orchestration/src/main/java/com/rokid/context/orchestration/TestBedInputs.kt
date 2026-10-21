package com.rokid.context.orchestration

import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.nudge.NudgeInputs
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.model.ModelImageInput
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars

/** spec §4.5 test bed inputs: a recent frame, free text, or a canned question (ANSWER uses real retrieval; NUDGE the engine's own inputs). */
sealed interface TestInput {
    data class Frame(val frameId: String) : TestInput
    data class Text(val text: String) : TestInput
    data class Question(val text: String) : TestInput
}

/** Builds the same delimited variables production uses, so a test run sees what the pipeline would send. */
class TestBedInputs(
    private val memory: MemoryGraph,
    private val answers: AnswerService,
    /** The production NUDGE inputs (Phase 7 C4 carry): pass the app's sources so the test bed sees what the engine sends. */
    private val nudges: NudgeInputs = NudgeInputs(memory, ContextSourceProvider.None),
    private val clock: () -> Long = System::currentTimeMillis,
) {
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
                // The same memories, recent_nudges and sources blocks as NudgeEngine, plus the typed line.
                val p = nudges.build(clock(), extraText = text)
                Prepared(p.vars, emptyList(), p.inputIds)
            }
            PromptKey.VISION_EXTRACT -> {
                val frame = frameFor(input)
                val lines = listOfNotNull(frame?.let(::frameLine), text.takeIf { it.isNotBlank() })
                Prepared(PromptVars(recentContext = PromptComposer.dataBlock("frames", lines)), frame?.let { FrameInputs.fromFrames(listOf(it), 1) }.orEmpty(), emptySet())
            }
            PromptKey.CLUSTER_SUMMARY -> {
                // A test run is one cluster of one photo, labelled like ClusterContract's lines.
                val frame = frameFor(input)
                val lines = listOfNotNull(frame?.let { "cluster_id: test frame_id: ${it.id} time: ${it.ts} source: ${it.source}" }, text.takeIf { it.isNotBlank() })
                Prepared(
                    PromptVars(recentContext = PromptComposer.dataBlock("photos", lines)), frame?.let { FrameInputs.fromFrames(listOf(it), 1) }.orEmpty(),
                    setOfNotNull("test", frame?.id),
                )
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
