package com.rokid.context.phone.ui.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.activity.ActivityModel
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.memory.MemoryDetailModel
import com.rokid.context.phone.ui.preview.PreviewSection.AudioSection
import com.rokid.context.phone.ui.preview.PreviewSection.ConfigSection
import com.rokid.context.phone.ui.preview.PreviewSection.ImageSection
import com.rokid.context.phone.ui.preview.PreviewSection.StatusSection
import com.rokid.context.phone.ui.preview.PreviewSection.TextSection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One item as the Activity page shows it, in a list AND in the previewer: the same presenter feeds both, so a row looks and opens the same everywhere.
 * [sections] is the ordered layout of the previewer (the screen never branches on the kind); [forgetTarget] is what its Forget would remove, null when the item has nothing to forget.
 */
interface Previewable {
    val id: String
    fun summary(): ListItem
    fun sections(): List<PreviewSection>
    fun forgetTarget(): ForgetTarget?
}

/** What a presenter needs to read and say. [withDay] asks for a day's photo ids (the previewer pages through them); a list leaves it off and reads no extra rows. */
class PreviewCtx(val data: ActivityData, val zone: ZoneId, val today: LocalDate, val withDay: Boolean = false) {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    /** "Today 09:30": the day word and the time. */
    fun at(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).let { z -> "${ActivityModel.dayLabel(z.toLocalDate(), today)} ${hhmm.format(z)}" }
    fun dayOf(ms: Long): LocalDate = DayBucket.dayOf(ms, zone)
    suspend fun dayIds(ms: Long): List<String> = if (withDay) data.dayFrameIds(dayOf(ms)) else emptyList()
}

/** Reads the items of one kind by id. A missing id is simply missing from the result. */
fun interface PreviewableFactory { suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> }

/** Kind to presenter: add a [ListKind] and register its factory here (the registry test fails until you do). */
object Previewables {
    private val registry: Map<ListKind, PreviewableFactory> = mapOf(
        ListKind.FRAMES to FramePreview,
        ListKind.MEMORIES to MemoryPreview.captions,
        ListKind.MOMENTS to MemoryPreview.moments,
        ListKind.MARKS to MarkPreview,
        ListKind.ANSWERS to AnswerPreview,
        ListKind.NUDGES to NudgePreview,
    )

    fun factory(kind: ListKind): PreviewableFactory = registry.getValue(kind)
    /** The presenter that opens an item named by a [PreviewRef]. */
    fun forRef(kind: RefKind): PreviewableFactory = factory(ListKind.forRef(kind, ListKind.MEMORIES))
    fun registered(): Set<ListKind> = registry.keys
}

/** A photo with no caption: its day's photos (swipe), and Forget photo. */
class FramePreview(override val id: String, private val frame: FrameEntity?, private val ctx: PreviewCtx, private val dayIds: List<String>) : Previewable {
    override fun summary() = ListItem(id, frame?.let { ctx.at(it.ts) } ?: "Photo", subtitle = frame?.placeLabel ?: if (frame == null) PHOTO_GONE else "Photo", icon = Icons.Outlined.Image)
    override fun sections() = listOf(ImageSection(dayIds.ifEmpty { listOf(id) }, startId = id), AudioSection)
    override fun forgetTarget(): ForgetTarget? = ForgetTarget.Photo(id)

    companion object : PreviewableFactory {
        const val PHOTO_GONE = "Photo expired"
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> {
            val byId = ctx.data.frames(ids).associateBy { it.id }
            // A frame retention deleted still opens (it says "Photo expired") when it was asked for by id.
            return ids.distinct().map { id -> FramePreview(id, byId[id], ctx, byId[id]?.let { ctx.dayIds(it.ts) }.orEmpty()) }.sortedByDescending { byId[it.id]?.ts ?: 0L }
        }
    }
}

/** A caption (memory) with its photo. The Memories list and the Moments list draw the same item, a Moment's row also saying whether a photo goes with it. */
class MemoryPreview(private val m: MemoryEntity, private val frame: FrameEntity?, private val ctx: PreviewCtx, private val moment: Boolean) : Previewable {
    override val id get() = m.id
    private val d = MemoryDetailModel.build(m, frame, ctx.zone)

    override fun summary() = if (moment) {
        ListItem(m.id, m.text, subtitle = ctx.at(m.createdAt) + if (m.frameId != null) " · with photo" else "", icon = Icons.Outlined.Psychology)
    } else {
        ListItem(m.id, m.text, subtitle = ctx.at(m.createdAt), icon = Icons.Outlined.Psychology, trailingText = "${(m.confidence * 100).toInt()}%")
    }

    /** Photo (its own: Forget photo is its action), the caption with Trust and Edit, its review state and tags, audio. */
    override fun sections() = listOfNotNull(
        m.frameId?.let { ImageSection(listOf(it), startId = it, canForgetPhoto = true) },
        TextSection(null, m.text, memoryId = m.id),
        ConfigSection(listOfNotNull(
            "Level" to "${d.tierLabel} · ${d.reviewLabel}${if (d.trusted) " · Trusted" else ""}",
            "Confidence" to "${(m.confidence * 100).toInt()}%",
            d.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.joinToString(" · ") },
            "Saved" to d.createdLabel,
        )),
        AudioSection,
    )

    /** The fact; a hidden memory has no fact to forget, only its photo. */
    override fun forgetTarget(): ForgetTarget? = if (d.editable) ForgetTarget.Fact(m.id) else m.frameId?.let { ForgetTarget.Photo(it) }

    companion object {
        private fun factory(moment: Boolean) = PreviewableFactory { ids, ctx ->
            val memories = ctx.data.memories(ids)
            val frames = ctx.data.frames(memories.mapNotNull { it.frameId }).associateBy { it.id }
            memories.sortedByDescending { it.createdAt }.map { MemoryPreview(it, it.frameId?.let(frames::get), ctx, moment) }
        }
        val captions = factory(moment = false)
        val moments = factory(moment = true)
    }
}

/** A mark: its Moment's caption if it has one; else its photo; else only why there is none and "Show that time". A mark itself is never deleted from here. */
class MarkPreview(private val row: MarkRowUi, private val inner: Previewable?) : Previewable {
    override val id get() = row.labelId
    override fun summary() = item(row)

    override fun sections(): List<PreviewSection> = inner?.sections() ?: when {
        row.frameId != null && row.reason == null -> listOf(ImageSection(listOf(row.frameId), startId = row.frameId), AudioSection)
        else -> listOf(
            TextSection("Time", row.time),
            StatusSection(row.reasonCode ?: "unknown", row.reason ?: "No photo", if (row.reasonCode == "pending") Severity.INFO else Severity.WARNING, showTimeOn = row.date),
            AudioSection,
        )
    }

    override fun forgetTarget(): ForgetTarget? = inner?.forgetTarget() ?: row.frameId?.takeIf { row.reason == null }?.let { ForgetTarget.Photo(it) }

    companion object : PreviewableFactory {
        /** A mark as a row (the Activity page's Marks section draws the same row). */
        fun item(row: MarkRowUi) = ListItem(
            row.labelId, row.joined, subtitle = row.reason, icon = Icons.Outlined.Bookmark,
            trailingText = if (row.code.isEmpty()) row.time else "${row.time} · ${row.code}",
        )

        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> = ctx.data.marks(ids).sortedByDescending { it.atMs }.map { row ->
            val memory = row.memoryId?.let { MemoryPreview.captions.load(listOf(it), ctx).firstOrNull() }
            MarkPreview(row, memory ?: row.frameId?.takeIf { row.reason == null }?.let { FramePreview.load(listOf(it), ctx).firstOrNull() })
        }
    }
}

/** An answer. It opens its own detail screen from [previewMoment]; its rows and sections are here for the list and for a later previewer. */
class AnswerPreview(private val a: AskEntity, private val ctx: PreviewCtx) : Previewable {
    override val id get() = a.id
    override fun summary() = ListItem(a.id, a.question, subtitle = ctx.at(a.ts), icon = Icons.Outlined.ChatBubbleOutline)
    override fun sections() = listOf(TextSection("Question", a.question), TextSection("Answer", a.answer), AudioSection)
    override fun forgetTarget(): ForgetTarget? = null

    companion object : PreviewableFactory {
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> = ctx.data.asks(ids).sortedByDescending { it.ts }.map { AnswerPreview(it, ctx) }
    }
}

/** A nudge: same as [AnswerPreview]. */
class NudgePreview(private val n: NudgeEntity, private val ctx: PreviewCtx) : Previewable {
    override val id get() = n.id
    override fun summary() = ListItem(n.id, n.text, subtitle = ctx.at(n.ts), icon = Icons.Outlined.Lightbulb)
    override fun sections() = listOf(TextSection(null, n.text), TextSection("Why", n.why), AudioSection)
    override fun forgetTarget(): ForgetTarget? = null

    companion object : PreviewableFactory {
        override suspend fun load(ids: List<String>, ctx: PreviewCtx): List<Previewable> = ctx.data.nudges(ids).sortedByDescending { it.ts }.map { NudgePreview(it, ctx) }
    }
}
