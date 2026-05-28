package com.rokid.context.model.lifecycle

enum class DownloadState {
    RUNNING,
    PAUSED,
}

class ModelDownloadManager {
    fun nextState(current: DownloadState, batteryPercent: Int, isCharging: Boolean): DownloadState {
        return if (batteryPercent <= 30 && !isCharging) DownloadState.PAUSED else current
    }
}
