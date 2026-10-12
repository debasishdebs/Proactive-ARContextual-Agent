package com.rokid.context.phone.ui

import com.rokid.context.phone.ui.status.PipelineControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePipelineControl(initial: Boolean = true) : PipelineControl {
    private val state = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) { state.value = enabled }
}
