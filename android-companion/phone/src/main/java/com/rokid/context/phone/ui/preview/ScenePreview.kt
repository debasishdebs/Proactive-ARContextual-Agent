package com.rokid.context.phone.ui.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import com.rokid.context.memory.SceneCard
import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.activity.ActivityModel
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.preview.PreviewSection.ConfigSection
import com.rokid.context.phone.ui.preview.PreviewSection.SourcesSection
import com.rokid.context.phone.ui.preview.PreviewSection.TextSection

/** The plain words of a Scene. Pure, so the list row and the previewer say the same thing and a test can pin it. */
object SceneWords {
    /** `HEAD_MOVING` as "Head moving"; null for none. */
    fun activity(code: String?): String? = code?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    /** How long a Scene ran: "40 s" under a minute, else whole minutes. */
    fun length(startMs: Long, endMs: Long): String {
        val s = ((endMs - startMs).coerceAtLeast(0L) + 999) / 1000
        return if (s < 60) "$s s" else "${(s + 30) / 60} min"
    }

    /** What cut the Scene, in words (a code this build does not know is "Other"). */
    fun rule(code: String): String = when (code) {
        SceneRule.ACTIVITY_CHANGE -> "Activity changed"
        SceneRule.FRAME_CHANGE -> "The view changed"
        SceneRule.CAP_5_MIN -> "5 minute limit"
        SceneRule.HOUR_END -> "End of the hour"
        else -> "Other"
    }
}

/**
 * A Scene as the Activity page shows it, in the list and in the previewer. Read only: its words are its fresh summary or caption (never a hidden one), its members are Moments by reference. It has NO Forget of its own here
 * ([forgetTarget] is null): Forget on a Scene stays the existing Developer-gated scope of a memory's Forget. No coordinates and no place are ever shown.
 */
class ScenePreview(override val id: String, private val card: SceneCard?, private val members: List<SourceItem>, private val ctx: PreviewCtx) : Previewable {
    private val s get() = card?.scene

    override fun summary(): ListItem {
        val sc = s ?: return ListItem(id, "Scene", subtitle = "This Scene is no longer available", icon = Icons.Outlined.Movie)
        val span = ctx.at(sc.startMs) + " to " + (if (ctx.dayOf(sc.endMs) == ctx.dayOf(sc.startMs)) ctx.clock(sc.endMs) else ctx.at(sc.endMs))
        val activity = SceneWords.activity(sc.activity)
        val n = card?.parts ?: 0
        val sub = listOfNotNull(span, activity, ActivityModel.plural(n, "moment")).joinToString(" · ")
        return ListItem(id, card?.text ?: activity?.let { "Scene: $it" } ?: "Scene", subtitle = sub, icon = Icons.Outlined.Movie)
    }

    override fun sections(): List<PreviewSection> {
        val sc = s ?: return listOf(TextSection(null, "This Scene is no longer available"))
        return listOfNotNull(
            card?.text?.let { TextSection("Summary", it) },
            ConfigSection(live = true, entries = listOfNotNull(
                "When" to ctx.full(sc.startMs),
                "Until" to ctx.clock(sc.endMs),
                "Length" to SceneWords.length(sc.startMs, sc.endMs),
                SceneWords.activity(sc.activity)?.let { "Activity" to it },
                "Moments" to "${card?.parts ?: 0}",
                "Cut by" to SceneWords.rule(sc.boundaryRule),
            )),
            members.takeIf { it.isNotEmpty() }?.let { SourcesSection("Moments in this scene", it) },
        )
    }

    /** None: Forget on a Scene is not widened by this list. */
    override fun forgetTarget(): ForgetTarget? = null

    companion object : PreviewableFactory {
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> {
            val cards = ctx.data.scenes(ids)
            // A list reads only the rows; the previewer (withDay) also reads the members of the one Scene it shows.
            return cards.map { c -> ScenePreview(c.scene.sceneId, c, if (ctx.withDay) members(c, ctx) else emptyList(), ctx) }
        }

        private suspend fun members(card: SceneCard, ctx: PreviewCtx): List<SourceItem> {
            val ms = ctx.data.sceneMembers(card.scene.sceneId)
            if (ms.isEmpty()) return emptyList()
            val memories = ctx.data.memories(ms.flatMap { it.memoryIds }).filter { it.reviewState == ReviewState.OK }.associateBy { it.id }
            return ms.map { m -> item(m, m.memoryIds.firstNotNullOfOrNull(memories::get), ctx) }
        }

        /** A member Moment as a tappable card: its first visible caption (a tap opens that memory's preview), else its photo, else its mark. */
        internal fun item(m: SceneMember, memory: MemoryEntity?, ctx: PreviewCtx): SourceItem {
            val time = ctx.clock(m.atMs)
            return when {
                memory != null -> SourceItem(PreviewRef.of(RefKind.MEMORY, memory.id), "$time  ${memory.text}", frameId = memory.frameId ?: m.frameIds.firstOrNull())
                m.frameIds.isNotEmpty() -> SourceItem(PreviewRef.of(RefKind.FRAME, m.frameIds.first()), "$time  Photo", frameId = m.frameIds.first())
                m.markIds.isNotEmpty() -> SourceItem(PreviewRef.of(RefKind.MARK, m.markIds.first()), "$time  Mark")
                else -> SourceItem(null, "$time  Moment")
            }
        }
    }
}
