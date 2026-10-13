package com.rokid.context.memory

import android.content.Context
import com.rokid.context.memory.db.PrefsModeStore

/** Settings → Memory "Encrypt memory" (spec §2.4): the request is applied when the app next starts (EncryptionMigrator). */
object DbEncryption {
    data class Status(val encrypted: Boolean?, val pendingTarget: Boolean?, val lastChangeFailed: Boolean)

    fun status(context: Context): Status = PrefsModeStore(context).let { Status(it.current, it.target, it.failed) }

    fun request(context: Context, encrypted: Boolean) {
        val store = PrefsModeStore(context)
        store.target = if (store.current == encrypted) null else encrypted
        store.failed = false
    }
}
