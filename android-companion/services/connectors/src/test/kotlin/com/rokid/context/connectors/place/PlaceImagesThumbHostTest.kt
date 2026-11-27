package com.rokid.context.connectors.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaceImagesThumbHostTest {
    private fun body(thumb: String) =
        """{"query":{"pages":[{"title":"File:A.jpg","imageinfo":[{"thumburl":"$thumb","mime":"image/jpeg","descriptionurl":"https://commons.wikimedia.org/wiki/File:A.jpg","extmetadata":{"LicenseShortName":{"value":"CC BY-SA 3.0"}}}]}]}}"""

    @Test fun aThumbHostLinkIsReadAsTheUploadHost() {
        val f = PlaceImageHttpSource.parseCommons(body("https://thumb.wikimedia.org/wikipedia/commons/thumb/7/71/A.jpg/330px-A.jpg")).getValue("A.jpg")
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/7/71/A.jpg/330px-A.jpg", f.thumbUrl)
    }

    @Test fun anUploadHostLinkStaysAndAnotherHostIsDropped() {
        assertNotNull(PlaceImageHttpSource.parseCommons(body("https://upload.wikimedia.org/x/A.jpg")).getValue("A.jpg").thumbUrl)
        assertEquals(null, PlaceImageHttpSource.parseCommons(body("https://evil.example/x/A.jpg")).getValue("A.jpg").thumbUrl)
    }
}
