package com.rokid.context.phone.ui.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.AskSourceStates
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.orchestration.AskSources
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.memory.MemoryDetailModel
import com.rokid.context.phone.ui.home.ANSWER_FROM_FORGOTTEN
import com.rokid.context.phone.ui.home.REDACTED_NUDGE
import com.rokid.context.phone.ui.preview.PreviewSection.ForgottenAnswerSection
import com.rokid.context.phone.ui.preview.PreviewSection.AudioSection
import com.rokid.context.phone.ui.preview.PreviewSection.ProposalSection
import com.rokid.context.phone.ui.preview.PreviewSection.RatingSection
import com.rokid.context.phone.ui.preview.PreviewSection.SourcesSection
import com.rokid.context.phone.ui.preview.PreviewSection.TextSection

/** What an answer or a nudge cited, read once: the memories and photos its cited ids name. A forgotten or invalidated memory is gone from here (the "no longer available" count). */
class Cited(private val ids: List<String>, private val memories: Map<String, MemoryEntity>, private val frames: Map<String, FrameEntity>) {
    val items: List<SourceItem> = ids.mapNotNull { id ->
        if (id.startsWith(PHOTO_CITE_PREFIX)) return@mapNotNull photo(id.removePrefix(PHOTO_CITE_PREFIX))
        memories[id]?.takeIf { it.reviewState != ReviewState.INVALIDATED }?.let { m ->
            SourceItem(PreviewRef.of(RefKind.MEMORY, m.id), m.text, m.confidence, m.frameId?.takeIf { it in frames }, large = m.frameId != null && m.type == "caption")
        }
    }
    val missing: Int get() = ids.size - items.size

    /** A cited photo is never "missing": a gone photo keeps its card as the inert "Photo expired" state. */
    private fun photo(frameId: String): SourceItem =
        frames[frameId]?.let { SourceItem(PreviewRef.of(RefKind.FRAME, frameId), "Photo", null, frameId, large = true, photoId = frameId) }
            ?: SourceItem(null, MemoryDetailModel.PHOTO_EXPIRED, expired = true, large = true, photoId = frameId)

    companion object {
        suspend fun load(json: String, redacted: Boolean, ctx: PreviewCtx): Cited {
            val ids = if (redacted) emptyList() else AskSources.parse(json).distinct()
            val memories = ctx.data.memories(ids.filterNot { it.startsWith(PHOTO_CITE_PREFIX) }).associateBy { it.id }
            val frameIds = ids.filter { it.startsWith(PHOTO_CITE_PREFIX) }.map { it.removePrefix(PHOTO_CITE_PREFIX) } + memories.values.mapNotNull { it.frameId }
            return Cited(ids, memories, ctx.data.frames(frameIds).associateBy { it.id })
        }
    }
}

/** An answer: the question, the answer with its rating, the action card, and what it was based on (each source opens its own previewer). A redacted answer shows its words only. Nothing here forgets. */
class AnswerPreview(private val a: AskEntity, private val cited: Cited, private val ctx: PreviewCtx) : Previewable {
    override val id get() = a.id
    private val forgotten = AskSourceStates.isForgotten(a)
    /** The Activity list line: an answer whose source was forgotten says so in its subtitle (the question stays, the answer is gone). */
    override fun summary() = ListItem(a.id, a.question, subtitle = if (forgotten) "${ctx.at(a.ts)} · $ANSWER_FROM_FORGOTTEN" else ctx.at(a.ts), icon = Icons.Outlined.ChatBubbleOutline)
    override fun sections() = listOfNotNull(
        TextSection("You asked · ${ctx.full(a.ts)}", a.question),
        if (forgotten) ForgottenAnswerSection(a.question, AskSourceStates.isReasked(a)) else TextSection("Answer", a.answer),
        RatingSection(a.rating).takeIf { !a.redacted },
        ProposalSection(a.id),
        SourcesSection("Based on", cited.items, cited.missing, moreQuery = a.question).takeIf { !a.redacted },
        AudioSection,
    )
    /** No Forget of its own (v1.1 backlog: Forget single answer/nudge): an answer is redacted when a source it cites is forgotten. */
    override fun forgetTarget(): ForgetTarget? = null

    companion object : PreviewableFactory {
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> =
            ctx.data.asks(ids).sortedByDescending { it.ts }.map { AnswerPreview(it, Cited.load(it.sourcesJson, it.redacted, ctx), ctx) }
    }
}

/** A nudge: its text, why, what it was based on and its action card. A redacted nudge shows only that it was removed. */
class NudgePreview(private val n: NudgeEntity, private val cited: Cited, private val ctx: PreviewCtx) : Previewable {
    override val id get() = n.id
    override fun summary() = ListItem(n.id, n.text, subtitle = ctx.at(n.ts), icon = Icons.Outlined.Lightbulb)
    override fun sections() = if (n.redacted) listOf(TextSection(ctx.full(n.ts), REDACTED_NUDGE), AudioSection) else listOfNotNull(
        TextSection(ctx.full(n.ts), n.text),
        TextSection("Why", n.why).takeIf { n.why.isNotBlank() },
        SourcesSection("Based on", cited.items).takeIf { cited.items.isNotEmpty() },
        ProposalSection(n.id),
        AudioSection,
    )
    override fun forgetTarget(): ForgetTarget? = null

    companion object : PreviewableFactory {
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> =
            ctx.data.nudges(ids).sortedByDescending { it.ts }.map { NudgePreview(it, Cited.load(it.sourceIdsJson, it.redacted, ctx), ctx) }
    }
}
