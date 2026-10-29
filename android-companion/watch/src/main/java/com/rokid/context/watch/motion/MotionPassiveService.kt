package com.rokid.context.watch.motion

// Provenance: ported from live-fitness-tracker watch/.../motion/MotionPassiveService.kt.

import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Instant

/** Bound by Health Services only (manifest permission); events go to [PassiveMotion]. */
class MotionPassiveService : PassiveListenerService() {
    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        PassiveMotion.onEvents(
            this,
            listOf(MotionEvent.Activity(activity(info.userActivityState), kind(info.exerciseInfo?.exerciseType), info.stateChangeTime.toEpochMilli())),
        )
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val events = dataPoints.getData(DataType.STEPS)
            .sortedBy { it.startDurationFromBoot }
            .map { MotionEvent.Steps(it.value, it.getStartInstant(boot).toEpochMilli(), it.getEndInstant(boot).toEpochMilli()) }
        if (events.isNotEmpty()) PassiveMotion.onEvents(this, events)
    }

    override fun onPermissionLost() {
        Log.i(PassiveMotion.TAG, "permission lost")
    }

    private fun activity(s: UserActivityState): UserActivity = when (s) {
        UserActivityState.USER_ACTIVITY_ASLEEP -> UserActivity.ASLEEP
        UserActivityState.USER_ACTIVITY_EXERCISE -> UserActivity.EXERCISE
        UserActivityState.USER_ACTIVITY_PASSIVE -> UserActivity.PASSIVE
        else -> UserActivity.UNKNOWN
    }

    private fun kind(t: ExerciseType?): ExerciseKind? = when (t) {
        null -> null
        ExerciseType.RUNNING, ExerciseType.RUNNING_TREADMILL -> ExerciseKind.RUN
        ExerciseType.WALKING, ExerciseType.HIKING -> ExerciseKind.WALK
        ExerciseType.BIKING, ExerciseType.MOUNTAIN_BIKING -> ExerciseKind.BIKE
        else -> ExerciseKind.OTHER
    }
}
