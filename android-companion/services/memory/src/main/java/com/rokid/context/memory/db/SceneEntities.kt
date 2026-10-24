package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Scenes (Room v11, additive). A Scene is a stretch of the user's life: consecutive Moments of one hour window, cut by the rules of the scene design (activity change, frame change,
 * 5-minute cap, hour end). It is a Moment-family object (kind SCENE) that holds REFERENCES to its parts, never copies; its collective summary, caption and embedding live in
 * `object_derived` and its operations in `object_audit`. Names, ids and times only here: no text, no labels, no photo data.
 */

/** Why a Scene ended (the rule that cut it). The last Scene of an hour window ends by [HOUR_END]. */
object SceneRule {
    const val ACTIVITY_CHANGE = "ACTIVITY_CHANGE"
    const val FRAME_CHANGE = "FRAME_CHANGE"
    const val CAP_5_MIN = "CAP_5_MIN"
    const val HOUR_END = "HOUR_END"
    val ALL = listOf(ACTIVITY_CHANGE, FRAME_CHANGE, CAP_5_MIN, HOUR_END)
}

/**
 * One stored Scene. [startMs] and [endMs] are on the phone clock (the first member's second start and the last member's second end); [hourStartMs] is the clock hour window it lives in.
 * [boundaryRule] is a [SceneRule]; [startTrigger] and [endTrigger] are optional text codes (for example a head flip) that nothing writes yet. [activity] is the dominant motion
 * state of the members, or null when none was logged. [membershipRev] counts membership changes (1 when built, +1 for every change of the member set, also a Forget shrink).
 */
@Entity(tableName = "scene", indices = [Index("hourStartMs"), Index("startMs")])
data class SceneEntity(
    @PrimaryKey val sceneId: String,
    override val buildRev: Int,
    val hourStartMs: Long,
    val startMs: Long,
    val endMs: Long,
    val boundaryRule: String,
    val startTrigger: String? = null,
    val endTrigger: String? = null,
    val activity: String? = null,
    val membershipRev: Int,
    val builtAt: Long,
) : com.rokid.context.memory.moment.MomentObject {
    override val kind: String get() = com.rokid.context.memory.moment.ObjectKind.SCENE
    override val id: String get() = sceneId
}

/** Part kinds of a Scene. AUDIO and SENSOR are reserved (same shape, no writer, no schema work yet). */
object ScenePartKind {
    const val MOMENT = "MOMENT"
    const val AUDIO = "AUDIO"
    const val SENSOR = "SENSOR"
}

/** A membership edge: Scene [sceneId] holds [refId] (a Moment id for kind MOMENT). [partRev] fingerprints the part's content (the Moment's content hash); [partTimeMs] is its time. */
@Entity(tableName = "scene_part", primaryKeys = ["sceneId", "kind", "refId"], indices = [Index("sceneId"), Index("kind", "refId")])
data class ScenePartEntity(
    val sceneId: String,
    val kind: String,
    val refId: String,
    val partRev: String,
    val partTimeMs: Long,
)
