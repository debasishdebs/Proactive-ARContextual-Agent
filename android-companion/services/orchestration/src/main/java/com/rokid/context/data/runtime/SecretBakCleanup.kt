package com.rokid.context.data.runtime

import android.content.Context
import com.rokid.context.core.log.PcLog

/**
 * B12: deletes copies of the legacy key file (`files/app_config_secret.xml.bak.*`) that an Auto Backup restore put
 * back. No code in this app writes them (BackupRulesTest pins that); they came from outside the app on an old install.
 * The legacy-key retirement (LegacyKeySeed) reads shared_prefs/app_config_secret.xml only, never these, so they go
 * straight away. Idempotent; never opens a file, and logs a count only (no names, no contents). Never throws.
 */
object SecretBakCleanup {
    const val TAG = "SecretBakCleanup"
    private const val PREFIX = "app_config_secret.xml.bak"

    /** Returns how many copies were deleted. */
    fun run(context: Context): Int = runCatching {
        val copies = context.filesDir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }.orEmpty()
        val deleted = copies.count { it.delete() }
        if (deleted > 0) PcLog.i(TAG, "Deleted $deleted restored secret backup file(s)")
        deleted
    }.getOrDefault(0)
}
