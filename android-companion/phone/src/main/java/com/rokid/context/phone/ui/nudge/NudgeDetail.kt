package com.rokid.context.phone.ui.nudge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.shell.PhoneDeps

/** Nudge detail (`nudge/{id}`). Placeholder (Stream 0): header and Back only. Stream C fills it. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun NudgeDetailRoute(id: String, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) { ScreenHeader("Nudge", onBack = back) }
}
