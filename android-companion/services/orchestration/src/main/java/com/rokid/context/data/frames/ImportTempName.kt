package com.rokid.context.data.frames

import java.util.concurrent.ConcurrentHashMap

/**
 * The dump import's publish temp `.imp-<id>-<creation wall ms>.<ext>` (and its atomic-writer sibling `.imp-<id>-<ms>.<ext>.part`): its age comes from the name (its mtime is the ORIGINAL capture time). A temp this process
 * is writing right now is [begin]-registered and is never swept, whatever the clock or its age says.
 */
object ImportTempName {
    private val PATTERN = Regex("-(\\d{10,})\\.[a-z]+(\\.part)?$")
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun createdAt(name: String): Long? = PATTERN.find(name)?.groupValues?.get(1)?.toLongOrNull()
    /** Registers the temp [name] (and its `.part` sibling) as in progress. */
    fun begin(name: String) { active += name }
    fun end(name: String) { active -= name }
    fun isActive(name: String): Boolean = name in active || (name.endsWith(".part") && name.removeSuffix(".part") in active)
    /** Whether the orphan `.part` sweep must leave [name] alone (an import temp is the import sweep's business). */
    fun isImportTemp(name: String): Boolean = name.startsWith(".imp-")
}
