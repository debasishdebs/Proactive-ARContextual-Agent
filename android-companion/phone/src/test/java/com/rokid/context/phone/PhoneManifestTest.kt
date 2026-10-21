package com.rokid.context.phone

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** R12 (2026-10-21): the phone does not ask for READ_PHONE_STATE, and does not strip it from a merged library either. */
class PhoneManifestTest {
    @Test fun noReadPhoneStateRequested() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("READ_PHONE_STATE"))
        // Not even a `tools:node="remove"` element for it: any mention of the permission name fails, and other removals stay allowed.
    }
}
