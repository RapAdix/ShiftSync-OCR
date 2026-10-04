package com.example.workflowocr

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals

/** Snackbar styling follows its severity, so translating the message cannot change its color. */
data class AppSnackbarVisuals(
    override val message: String,
    val isError: Boolean = false,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals
