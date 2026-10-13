package com.rokid.context.phone.ui.settings.flows

import androidx.compose.runtime.Composable

/** Editors for `Facet.Custom(editorId)` steps (future ML models), registered by id. None on day one. */
object FacetEditors {
    val all: Map<String, @Composable () -> Unit> = emptyMap()
}
