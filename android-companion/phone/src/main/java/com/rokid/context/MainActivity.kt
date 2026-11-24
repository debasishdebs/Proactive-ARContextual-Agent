package com.rokid.context

import android.os.Bundle
import com.rokid.context.core.log.PcLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.phone.ui.shell.AppShell
import com.rokid.context.phone.ui.onboarding.OnboardingGuard
import com.rokid.context.phone.ui.shell.BluetoothPermission
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Thin host (spec §1.4): one-time cleanup + worker scheduling, then the NavHost shell. No polling, no UI state here. */
class MainActivity : ComponentActivity() {
    /** Missing BLUETOOTH_CONNECT never blocks the app; the shell shows a hint until it is granted. */
    private val bluetoothMissing = MutableStateFlow(false)
    private val reonboard = MutableStateFlow(false)
    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bluetoothMissing.value = !granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Legacy cleanup once per launch (not on configuration changes), off the main thread (Room-backed calls may
        // wait on the one-time import).
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                QueueRuntimeStore.purgeSynthetic(this@MainActivity)
                PipelineRecords.purgeNonRokidFrames(this@MainActivity)
                PipelineRecords.purgeSyntheticTraces(this@MainActivity)
                // Codex v3 r1 P1-6: no automatic memory purge at launch any more (only an explicit, previewed Forget deletes text)
                // Phase 7 Stream F: photo sweep, pending re-summarise, retention schedule (whatever the pipeline state).
                val app = applicationContext
                com.rokid.context.orchestration.forget.ForgetStartup.run(app) { com.rokid.context.phone.hub.HubGraph.get(app).refresh() }
            }
        }
        val deps = PhoneDeps.get(this)
        // The legacy app re-scheduled workers on every launch even when the pipeline was off; honour the switch.
        // Phase 7: processing follows the pipeline flag; capture pauses (incl. quiet hours) don't stop it.
        if (deps.pipeline.processingEnabled.value) {
            OrchestrationScheduler(this).apply { scheduleFrameIngest(); scheduleQueueDrain(); scheduleCritic() }
        }
        val complete = deps.onboarding.isComplete()
        val start = Routes.startRoute(complete)
        // BUG-R3-1: a "complete" restored by Auto Backup onto a fresh install (no key, empty DB) is not believed.
        if (savedInstanceState == null && complete) {
            lifecycleScope.launch(Dispatchers.IO) {
                val rerun = runCatching {
                    OnboardingGuard.of(com.rokid.context.ai.AiGraph.get(applicationContext), deps.memory).mustRerun(complete = true)
                }.getOrElse { e -> PcLog.w(TAG, "onboarding check failed (${e.javaClass.simpleName})"); false }
                if (rerun) {
                    PcLog.i(TAG, "onboarding marked complete but no usable AI and no data: showing onboarding")
                    deps.onboarding.resetDurably()
                    reonboard.value = true
                }
            }
        }
        setContent { PcTheme { AppShell(deps, start, bluetoothMissing = bluetoothMissing, reonboard = reonboard) } }
        // Asked once per fresh launch (not on rotation); after a permanent denial the system answers "denied" silently.
        if (savedInstanceState == null && BluetoothPermission.isMissing(this)) {
            runCatching { requestBluetooth.launch(BluetoothPermission.PERMISSION) }.onFailure { bluetoothMissing.value = true }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a grant made in system settings (or a revoke) when the user comes back.
        bluetoothMissing.value = BluetoothPermission.isMissing(this)
    }

    private companion object { const val TAG = "MainActivity" }
}
