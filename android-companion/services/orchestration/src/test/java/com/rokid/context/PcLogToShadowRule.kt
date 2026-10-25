package com.rokid.context

import android.util.Log
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Test seam: routes PcLog lines into android.util.Log so a Robolectric test can read them with ShadowLog (production installs the file sink). Diagnostics on, so D and I lines show. */
class PcLogToShadowRule : TestWatcher() {
    override fun starting(description: Description) {
        PcLog.testOnlyResetNeverCallFromProduction()
        PcLog.install(object : LogSink {
            override fun emit(line: Line, route: Route) {
                when (line.level) { Level.D -> Log.d(line.tag, line.msg); Level.I -> Log.i(line.tag, line.msg); Level.W -> Log.w(line.tag, line.msg); Level.E -> Log.e(line.tag, line.msg) }
            }
        })
        PcLog.setDiagnostic(true)
    }
    override fun finished(description: Description) { PcLog.testOnlyResetNeverCallFromProduction() }
}
