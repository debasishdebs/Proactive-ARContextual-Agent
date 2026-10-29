package com.rokid.context.phone.context

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Data Layer receiver for the PCA watch app (`/pca/motion`, watch to phone). The platform delivers only messages from an app with
 * the same applicationId and signing key, so there is no caller gate; [PcaWatchLink.handle] does everything else.
 */
class PcaWatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val on = ContextSourceSettings.of(this).activeWatchSource == ContextSourceSettings.WATCH_APP
        PcaWatchLink.handle(event.path, event.data, on)
    }
}
