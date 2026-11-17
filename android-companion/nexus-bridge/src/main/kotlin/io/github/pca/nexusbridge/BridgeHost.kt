package io.github.pca.nexusbridge

import android.util.Log

/** Process-wide link between the two services: the port service (PCA side) and the Nexus plugin service (hub side). Holds no content. */
object BridgeHost {
    @Volatile var bus: NexusBusClient? = null
    val renderer = BridgeRenderer(bus = { bus }, log = { Log.i("PCABRIDGE", it) })
}
