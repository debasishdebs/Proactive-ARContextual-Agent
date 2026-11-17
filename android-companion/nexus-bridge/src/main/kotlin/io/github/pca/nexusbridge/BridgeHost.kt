package io.github.pca.nexusbridge

import android.util.Log

/** Process-wide link between the two services: the port service (PCA side) and the Nexus plugin service (hub side). Holds no content. */
object BridgeHost {
    @Volatile var bus: NexusBusClient? = null
    @Volatile var skillHost: SkillHostClient? = null
    /** The phone's answer to "may a speech session open now?" (set with the skill host; null = no). */
    @Volatile var listenAuth: (() -> Boolean)? = null
    val renderer = BridgeRenderer(bus = { bus }, log = { Log.i("PCABRIDGE", it) }, authorizeListen = { listenAuth?.invoke() ?: false })
    val skills = SkillRouter(host = { skillHost }, features = { renderer.currentFeatures }, log = { Log.i("PCABRIDGE", it) })
}
