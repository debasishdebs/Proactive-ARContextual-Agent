package com.rokid.context.phone.ui

import com.rokid.context.phone.ui.status.PipelineControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [publishAtOnce] false = a slow persist: requests are recorded, [enabled] changes only on [publish]. */
class FakePipelineControl(initial: Boolean = true, private val publishAtOnce: Boolean = true) : PipelineControl {
    private val state = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    val requests = mutableListOf<Boolean>()
    private var requested = initial
    override fun setEnabled(enabled: Boolean) {
        requests += enabled
        requested = enabled
        if (publishAtOnce) state.value = enabled
    }
    override fun toggle() = setEnabled(!requested)
    fun publish() { state.value = requested }
}
