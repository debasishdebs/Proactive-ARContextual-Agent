package com.rokid.context.data.frames

/** The dump import's publish temp `.imp-<id>-<creation wall ms>.<ext>`: its age comes from the name (its mtime is the ORIGINAL capture time). */
object ImportTempName {
    private val PATTERN = Regex("-(\\d{10,})\\.[a-z]+$")
    fun createdAt(name: String): Long? = PATTERN.find(name)?.groupValues?.get(1)?.toLongOrNull()
}
