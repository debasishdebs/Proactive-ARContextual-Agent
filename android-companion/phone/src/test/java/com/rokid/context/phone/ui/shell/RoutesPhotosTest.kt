package com.rokid.context.phone.ui.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `Routes.photos` encodes with `android.net.Uri.encode` (open source first), so it needs the Robolectric Uri. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoutesPhotosTest {
    @Test fun photosRouteEncodesTheQuestion() {
        assertEquals("photos", Routes.photos(""))
        assertEquals("photos?q=where%20are%20my%20keys%3F%20%26%20wallet", Routes.photos("where are my keys? & wallet"))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.PHOTOS))
    }
}
