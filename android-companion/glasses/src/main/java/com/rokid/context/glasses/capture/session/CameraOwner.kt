package com.rokid.context.glasses.capture.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exactly one camera owner at a time ("session" | "fixed"); [acquire] waits for the current owner to finish. */
class CameraOwner {
    private val mutex = Mutex()
    @Volatile var current: String? = null
        private set

    suspend fun <T> acquire(owner: String, block: suspend () -> T): T = mutex.withLock {
        current = owner
        try {
            block()
        } finally {
            current = null
        }
    }
}
