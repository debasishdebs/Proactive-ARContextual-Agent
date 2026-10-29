package com.rokid.context.phone

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** READ_PHONE_STATE is declared (relaunch guard call state); it is requested at runtime in Settings and denial never blocks anything. */
class PhoneManifestTest {
    @Test fun readPhoneStateIsDeclared() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.READ_PHONE_STATE\" />"))
    }

    @Test fun watchListenerIsDeclaredForThePcaMotionPathAndTheProviderStaysUntilSliceC() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val svc = manifest.substringAfter("com.rokid.context.phone.context.PcaWatchListener").substringBefore("</service>")
        assertTrue(svc.contains("com.google.android.gms.wearable.MESSAGE_RECEIVED"))
        assertTrue(svc.contains("android:pathPrefix=\"/pca/motion\""))
        assertTrue(manifest.contains("com.rokid.context.phone.context.WatchMotionProvider"))
    }
}
