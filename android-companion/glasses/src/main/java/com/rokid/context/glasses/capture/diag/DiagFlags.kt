package com.rokid.context.glasses.capture.diag

import java.io.File

/** Diagnostics switches read from marker files in the app's files dir (no UI, no wire). */
object DiagFlags {
    /** Touch this file in filesDir to get the old one-line-per-second `features` log back. */
    const val VERBOSE_FEATURES_FILE = "diag_verbose_features"

    fun verboseFeatures(filesDir: File): Boolean = runCatching { File(filesDir, VERBOSE_FEATURES_FILE).exists() }.getOrDefault(false)
}
