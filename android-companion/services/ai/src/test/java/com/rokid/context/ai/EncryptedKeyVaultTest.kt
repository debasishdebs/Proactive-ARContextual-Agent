package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EncryptedKeyVaultTest {
    /** F18: AiGraph.get builds the vault on Main (MainActivity.onCreate); building it must not open the Keystore. */
    @Test fun constructingTheVaultTouchesNoKeystore() {
        EncryptedKeyVault(ApplicationProvider.getApplicationContext()) // Robolectric has no AndroidKeyStore: an eager open throws here
    }
}
