package com.rokid.context.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Switch
import com.rokid.context.core.model.context.WatchState
import com.rokid.context.watch.motion.StateIcon
import com.rokid.context.watch.motion.WatchLook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.rokid.context.watch.motion.LinkStatus
import com.rokid.context.watch.motion.PassiveMotion
import com.rokid.context.watch.motion.WatchStatus
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PassiveMotion.register(this)
        setContent { PcaWatchTheme { StatusScreen() } }
    }

    override fun onResume() {
        super.onResume()
        PassiveMotion.register(this)
    }
}

private fun c(v: Long) = Color(v)

private val DarkColors = Colors(
    primary = c(0xFF7FA6D9L), background = c(WatchLook.BACKGROUND), surface = c(WatchLook.SURFACE),
    onBackground = c(WatchLook.TEXT), onSurface = c(WatchLook.TEXT), onSurfaceVariant = c(WatchLook.TEXT_DIM),
    onPrimary = Color.Black, error = c(WatchLook.WARN),
)

/** Dark theme (OLED: black background, nothing bright by default). */
@Composable
private fun PcaWatchTheme(content: @Composable () -> Unit) = MaterialTheme(colors = DarkColors, content = content)

private fun iconRes(i: StateIcon): Int = when (i) {
    StateIcon.STILL -> R.drawable.ic_state_still
    StateIcon.WALK -> R.drawable.ic_state_walk
    StateIcon.RUN -> R.drawable.ic_state_run
    StateIcon.CYCLE -> R.drawable.ic_state_cycle
    StateIcon.CAR -> R.drawable.ic_state_car
    StateIcon.EXERCISE -> R.drawable.ic_state_exercise
    StateIcon.SLEEP -> R.drawable.ic_state_sleep
    StateIcon.UNKNOWN -> R.drawable.ic_state_unknown
}

/**
 * Status screen: large state icon and name in the state's accent, the link line (with a one-tap fix when there is one), "Last sent", and the on/off toggle.
 * Reads the persisted MotionMemory; computes nothing itself. No animations; it refreshes on resume and every 15 s while visible.
 */
@Composable
private fun StatusScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(PassiveMotion.isEnabled(ctx)) }
    var granted by remember { mutableStateOf(PassiveMotion.hasPermission(ctx)) }
    var memory by remember { mutableStateOf(PassiveMotion.load(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) PassiveMotion.register(ctx)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                memory = PassiveMotion.load(ctx)
                granted = PassiveMotion.hasPermission(ctx)
                enabled = PassiveMotion.isEnabled(ctx)
                delay(15_000)
            }
        }
    }
    val link = WatchStatus.link(enabled, granted, memory)
    val shown = memory.reported
    val style = WatchLook.style(shown)
    val accent = c(style.accent)
    val warn = link == LinkStatus.PERMISSION_NEEDED || link == LinkStatus.PHONE_NOT_REACHABLE
    val fix = WatchStatus.fix(link)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(c(WatchLook.BACKGROUND)),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        item {
            Icon(painterResource(iconRes(style.icon)), contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
        }
        item {
            Text(WatchStatus.stateLabel(shown), color = accent, style = MaterialTheme.typography.display2, textAlign = TextAlign.Center, maxLines = 1)
        }
        item {
            Text(
                WatchStatus.linkText(link), color = if (warn) c(WatchLook.WARN) else c(WatchLook.TEXT_DIM),
                style = MaterialTheme.typography.body2, textAlign = TextAlign.Center,
            )
        }
        if (fix != null) {
            item {
                Chip(
                    onClick = {
                        when (fix) {
                            WatchStatus.Fix.GRANT_PERMISSION -> launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            WatchStatus.Fix.TURN_ON -> { enabled = true; PassiveMotion.setEnabled(ctx, true) }
                        }
                    },
                    label = { Text(WatchStatus.fixLabel(fix), maxLines = 1) },
                    colors = ChipDefaults.chipColors(backgroundColor = accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        WatchStatus.lastSent(memory) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }?.let {
            item { Text(it, color = c(WatchLook.TEXT_DIM), style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) }
        }
        item {
            ToggleChip(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    PassiveMotion.setEnabled(ctx, it)
                },
                label = { Text("Send motion to phone", maxLines = 2) },
                toggleControl = { Switch(checked = enabled) },
                colors = ToggleChipDefaults.toggleChipColors(checkedToggleControlColor = accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
