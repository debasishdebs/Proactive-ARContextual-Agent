package com.rokid.context.phone.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateForTest(): T = collectAsState().value
