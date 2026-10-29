package com.rokid.context.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
        setContent { MaterialTheme { StatusScreen() } }
    }

    override fun onResume() {
        super.onResume()
        PassiveMotion.register(this)
    }
}

/** Minimal status screen (design note section 6). Reads the persisted MotionMemory; computes nothing itself. */
@Composable
private fun StatusScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(PassiveMotion.isEnabled(ctx)) }
    var granted by remember { mutableStateOf(PassiveMotion.hasPermission(ctx)) }
    var memory by remember { mutableStateOf(PassiveMotion.load(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) PassiveMotion.register(ctx)
    }
    LaunchedEffect(Unit) {
        while (true) {
            memory = PassiveMotion.load(ctx)
            granted = PassiveMotion.hasPermission(ctx)
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val status = WatchStatus.link(enabled, granted, memory)
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { Text("Context", style = MaterialTheme.typography.title3) }
        item {
            Chip(
                onClick = {},
                label = {
                    Text(
                        when (status) {
                            LinkStatus.OFF -> "Off"
                            LinkStatus.PERMISSION_NEEDED -> "Permission needed"
                            LinkStatus.SENDING -> "Sending"
                            LinkStatus.PHONE_NOT_REACHABLE -> "Phone not reachable"
                            LinkStatus.WAITING -> "Waiting for motion"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Text(WatchStatus.stateLabel(memory.reported), style = MaterialTheme.typography.display2, textAlign = TextAlign.Center) }
        item { Text(WatchStatus.sentAgo(memory.lastSentMs, now), style = MaterialTheme.typography.caption2) }
        item {
            ToggleChip(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    PassiveMotion.setEnabled(ctx, it)
                },
                label = { Text("Share motion with PCA") },
                toggleControl = { androidx.wear.compose.material.Switch(checked = enabled) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!granted) {
            item {
                Chip(
                    onClick = { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                    label = { Text("Grant motion access") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
