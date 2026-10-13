package com.rokid.context.ai

import android.os.Looper
import java.util.Collections

/** Records, for every vault call, whether it ran on the main thread (F18: the Keystore is never touched on Main). */
class MainThreadVault(private val inner: KeyVault = InMemoryKeyVault()) : KeyVault {
    val onMain: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    private fun mark() { onMain += Looper.getMainLooper().isCurrentThread }
    override fun get(ref: String): String? { mark(); return inner.get(ref) }
    override fun put(ref: String, key: String) { mark(); inner.put(ref, key) }
    override fun remove(ref: String) { mark(); inner.remove(ref) }
}
