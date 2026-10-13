package com.rokid.context.connectors.sources

import android.content.Context
import com.rokid.context.connectors.ContextSourceProvider

/** The data sources' process-wide graph (R-64). */
interface SourcesGraphApi {
    val provider: ContextSourceProvider
}

/** Placeholder (Stream 0): no source lines. Stream C replaces the body with the real sources. */
object SourcesGraph {
    private val placeholder = object : SourcesGraphApi { override val provider: ContextSourceProvider = ContextSourceProvider.None }

    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): SourcesGraphApi = placeholder
}
