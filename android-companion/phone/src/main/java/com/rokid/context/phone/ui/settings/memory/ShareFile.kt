package com.rokid.context.phone.ui.settings.memory

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Opens the system share sheet for an export in cache/exports (FileProvider, read-only grant). */
object ShareFile {
    fun share(context: Context, file: File, mime: String = "application/json") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export memories").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
