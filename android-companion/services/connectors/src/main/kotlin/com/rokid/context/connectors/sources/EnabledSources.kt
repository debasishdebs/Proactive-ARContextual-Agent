package com.rokid.context.connectors.sources

import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.core.model.TextHygiene
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * `{{context_sources}}` from the enabled sources (R-64), in the order CALENDAR, NOTES, CONTACTS, LOCATION. A source
 * that is off contributes nothing and is never called; one that is on but lacks a permission contributes nothing and
 * shows in [needsPermission]; one that throws contributes nothing. Whole lines are dropped from the end past 1 500 chars.
 */
class EnabledSources(
    private val sources: List<ContextSource>,
    private val settings: SourceSettingsStore,
    private val granted: (String) -> Boolean,
    private val work: CoroutineDispatcher,
) : ContextSourceProvider {
    private val needs = MutableStateFlow<Set<SourceKind>>(emptySet())

    override suspend fun lines(question: String?, nowMs: Long): List<String> = withContext(work) {
        val on = settings.enabled.value
        recheck(on)
        val out = mutableListOf<String>()
        for (kind in ORDER) {
            if (kind !in on || kind in needs.value) continue
            val source = sources.firstOrNull { it.kind == kind } ?: continue
            out += try {
                source.lines(question, nowMs)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                emptyList() // SecurityException after a revoke, a provider crash: nothing, never a throw
            }
        }
        bounded(out.map { it.replace(TextHygiene.SEPARATORS, " ").trim() }.filter { it.isNotEmpty() })
    }

    /** Sources that are on but miss a permission right now (re-read on every call and every [lines]). */
    fun needsPermission(): StateFlow<Set<SourceKind>> { recheck(settings.enabled.value); return needs.asStateFlow() }

    private fun recheck(on: Set<SourceKind>) {
        needs.value = sources.filter { it.kind in on && !it.permissions.all(granted) }.map { it.kind }.toSet()
    }

    companion object {
        val ORDER = listOf(SourceKind.CALENDAR, SourceKind.NOTES, SourceKind.CONTACTS, SourceKind.LOCATION)
        const val MAX_CHARS = 1_500

        fun bounded(lines: List<String>, maxChars: Int = MAX_CHARS): List<String> {
            val kept = mutableListOf<String>()
            var size = 0
            for (l in lines) {
                val add = l.length + if (kept.isEmpty()) 0 else 1
                if (size + add > maxChars) break
                kept += l; size += add
            }
            return kept
        }
    }
}
