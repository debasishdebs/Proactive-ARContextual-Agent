package com.rokid.context.phone

/** Debug-only switches as pure functions of the build flag, so both values are testable (release passes false). */
internal object DebugOnlyGates {
    /** PcLink rx/tx probe lines on the glasses link: debug builds only. */
    fun linkProbe(debugBuild: Boolean): Boolean = debugBuild
    /** The "Copy frame id" button in the photo preview: debug builds only. */
    fun copyFrameIdVisible(debugBuild: Boolean): Boolean = debugBuild
}
