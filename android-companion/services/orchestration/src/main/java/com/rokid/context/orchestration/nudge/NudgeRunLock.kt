package com.rokid.context.orchestration.nudge

import kotlinx.coroutines.sync.Mutex

/**
 * One lock for every nudge run: [NudgeEngine.runOnce] holds it, so the periodic backstop worker and the fast
 * path ([NudgeEvaluator]'s evaluate step) can never run at once and race [NudgeGates]' daily cap.
 */
object NudgeRunLock {
    val mutex = Mutex()
}
