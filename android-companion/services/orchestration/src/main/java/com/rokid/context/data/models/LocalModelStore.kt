package com.rokid.context.data.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rokid.context.ai.local.LocalModel
import com.rokid.context.work.LocalModelDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface LocalModelState {
    data object Absent : LocalModelState
    /** [fraction] is 0..1, or null before the first progress report. */
    data class Downloading(val fraction: Float?) : LocalModelState
    data object Ready : LocalModelState
    /** [message] is always one of the worker's fixed user-safe strings, never raw error text. */
    data class Failed(val message: String) : LocalModelState
}

/** Downloaded on-device models in filesDir/models (excluded from every backup), and their download work. */
class LocalModelStore(context: Context) {
    private val app = context.applicationContext
    private val workManager get() = WorkManager.getInstance(app)

    /** The primary file (the one a llama.cpp endpoint loads); bundles carry more in [LocalModel.files]. */
    fun fileFor(model: LocalModel): File = File(modelsDir(app), model.file)

    private fun allFiles(model: LocalModel): List<File> = model.files.map { File(modelsDir(app), it.file) }

    fun state(model: LocalModel): Flow<LocalModelState> =
        combine(workManager.getWorkInfosForUniqueWorkFlow(LocalModelDownloadWorker.uniqueName(model.id)), fileChanges) { infos, _ ->
            stateOf(model, infos.lastOrNull())
        }.distinctUntilChanged()

    /** One download per model: a second request while one is queued or running is a no-op (KEEP). */
    fun enqueue(model: LocalModel, allowMetered: Boolean) = enqueue(model, allowMetered, ExistingWorkPolicy.KEEP)

    /**
     * The "Allow mobile data" switch for a download already queued or running: it is replaced by one with the new
     * network constraint (the stopped worker lets go of the .part first, and the new one resumes it). With nothing
     * queued, or the same constraint already, nothing happens. Never blocks the caller.
     */
    fun setAllowMetered(model: LocalModel, allowMetered: Boolean) {
        val infos = workManager.getWorkInfosForUniqueWork(LocalModelDownloadWorker.uniqueName(model.id))
        infos.addListener({
            val wanted = networkType(allowMetered)
            val live = runCatching { infos.get() }.getOrNull().orEmpty().filter { !it.state.isFinished }
            if (live.isNotEmpty() && live.any { it.constraints.requiredNetworkType != wanted }) enqueue(model, allowMetered, ExistingWorkPolicy.REPLACE)
        }, Runnable::run)
    }

    private fun enqueue(model: LocalModel, allowMetered: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setInputData(workDataOf(LocalModelDownloadWorker.KEY_MODEL_ID to model.id))
            // G14 (MVP section 14.1): a download also pauses while the battery is low and resumes from its .part after.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType(allowMetered)).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(LocalModelDownloadWorker.uniqueName(model.id), policy, request)
    }

    private fun networkType(allowMetered: Boolean) = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED

    /** Stops the download; the .part stays so a later Download resumes it. */
    fun cancel(model: LocalModel) {
        workManager.cancelUniqueWork(LocalModelDownloadWorker.uniqueName(model.id))
    }

    /** Cancels any download, then removes the model file and its .part. Callers unbind EMBED first. */
    fun delete(model: LocalModel) {
        cancel(model)
        for (f in allFiles(model)) {
            f.delete()
            LocalModelDownloadWorker.partFile(f).delete()
        }
        fileChanges.update { it + 1 }
    }

    private fun stateOf(model: LocalModel, info: WorkInfo?): LocalModelState {
        if (allFiles(model).all { it.isFile }) return LocalModelState.Ready
        return when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                LocalModelState.Downloading(info.progress.getFloat(LocalModelDownloadWorker.KEY_PROGRESS, -1f).takeIf { it >= 0f })
            WorkInfo.State.FAILED ->
                LocalModelState.Failed(info.outputData.getString(LocalModelDownloadWorker.KEY_MESSAGE) ?: LocalModelDownloadWorker.MSG_FAILED)
            else -> LocalModelState.Absent
        }
    }

    companion object {
        private const val TAG = "local_model"
        /** Bumped on delete, so every store's [state] re-reads the disk. */
        private val fileChanges = MutableStateFlow(0)

        fun modelsDir(context: Context): File = File(context.applicationContext.filesDir, "models")
    }
}
