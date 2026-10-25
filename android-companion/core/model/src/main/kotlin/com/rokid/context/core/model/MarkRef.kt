package com.rokid.context.core.model

/** The reference a mark photo carries (`FrameMeta.markRef`): `"<epoch>:<seq>"` (the glasses epoch is a UUID, the seq a positive number). One shape check for both ends. */
object MarkRef {
    private val SHAPE = Regex("^[A-Za-z0-9-]{1,64}:[0-9]{1,18}$")
    fun isValid(ref: String?): Boolean = ref != null && SHAPE.matches(ref)
    fun of(epoch: String, seq: Long): String = "$epoch:$seq"
    /** The phone's label id of the mark a valid [ref] names (`MarkRecorder.labelId`). */
    fun labelId(ref: String): String = "mk:$ref"
}
