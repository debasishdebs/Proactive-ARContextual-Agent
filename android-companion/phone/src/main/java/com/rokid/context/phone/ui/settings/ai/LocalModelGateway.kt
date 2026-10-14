package com.rokid.context.phone.ui.settings.ai

import com.rokid.context.ai.local.LocalModel
import com.rokid.context.data.models.LocalModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * What the On-device models page needs from `LocalModelStore`, member for member (`modelsDir(context)` is the store's
 * own business, so the page never asks for it). The app wires `StoreLocalModels` in `SettingsDeps.create`; tests keep a fake.
 */
interface LocalModelGateway {
    fun fileFor(model: LocalModel): File
    fun state(model: LocalModel): Flow<LocalModelState>
    fun enqueue(model: LocalModel, allowMetered: Boolean)
    /** Applies the mobile-data switch to a download already queued or running (no-op otherwise). */
    fun setAllowMetered(model: LocalModel, allowMetered: Boolean)
    fun cancel(model: LocalModel)
    fun delete(model: LocalModel)

    /** Nothing downloaded and no downloads (tests and previews). */
    object None : LocalModelGateway {
        override fun fileFor(model: LocalModel) = File(model.file)
        override fun state(model: LocalModel): Flow<LocalModelState> = MutableStateFlow(LocalModelState.Absent)
        override fun enqueue(model: LocalModel, allowMetered: Boolean) = Unit
        override fun setAllowMetered(model: LocalModel, allowMetered: Boolean) = Unit
        override fun cancel(model: LocalModel) = Unit
        override fun delete(model: LocalModel) = Unit
    }
}

/** The page's "Allow mobile data" switch (spec section 4: downloads are Wi-Fi only unless this is on). */
interface MobileDataSetting {
    fun allowed(): Boolean
    fun set(allowed: Boolean)
}

class InMemoryMobileData(private var on: Boolean = false) : MobileDataSetting {
    override fun allowed() = on
    override fun set(allowed: Boolean) { on = allowed }
}

/** A model's run-time load failures (spec section 6). [cause] is never shown: the page maps any failure to fixed text. */
data class LoadFailure(val consecutive: Int, val cause: Throwable? = null)

/**
 * Where the page learns that the on-device model failed to load. The app wires `NativeLoadFailures` (the embed path's
 * `LlamaCppNativeProvider.loadFailures`); tests and previews use a fake or [None].
 */
fun interface LoadFailures {
    suspend fun of(modelId: String): LoadFailure?

    companion object { val None = LoadFailures { null } }
}
