package com.rokid.context.phone.ui.settings.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsModel

/** Settings › Shopping list. Placeholder (Stream 0): header and "Coming soon". Stream B fills it. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShoppingRoute(deps: SettingsDeps, back: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) {
        ScreenHeader("Shopping list", onBack = back)
        Text(SettingsModel.COMING_SOON, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
    }
}
