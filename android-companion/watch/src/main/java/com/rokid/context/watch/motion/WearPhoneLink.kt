package com.rokid.context.watch.motion

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Sends to the connected node(s) of the paired phone. Message delivery is fire and forget; success means queued to a node. */
class WearPhoneLink(context: Context) : PhoneLink {
    private val app = context.applicationContext

    override suspend fun send(path: String, body: ByteArray): Boolean = try {
        val nodes = Wearable.getNodeClient(app).connectedNodes.await()
        var ok = false
        for (n in nodes) {
            try {
                Wearable.getMessageClient(app).sendMessage(n.id, path, body).await()
                ok = true
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (_: Exception) {
                // try the next node
            }
        }
        ok
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }
}
