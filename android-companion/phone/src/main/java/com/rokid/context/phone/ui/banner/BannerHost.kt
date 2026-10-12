package com.rokid.context.phone.ui.banner

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.Banner

/** Renders the single highest-severity banner under the header (spec §2.2); nothing when [spec] is null. */
@Composable
fun BannerHost(spec: BannerSpec?, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier) {
    if (spec == null) return
    val action = spec.action
    Banner(
        tone = spec.tone,
        title = spec.title,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        actionLabel = spec.actionLabel,
        onAction = if (action != null) ({ onAction(action) }) else null,
    )
}
