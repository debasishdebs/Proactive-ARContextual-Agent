package com.rokid.context

import com.rokid.context.ai.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest's network security config and UrlNormalizer's cleartext list must stay identical. */
class CleartextConfigTest {
    private val xml = File("src/main/res/xml/network_security_config.xml")

    @Test fun baseConfigForbidsCleartext() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test fun cleartextDomainsEqualTheNormalizerLists() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val domains = doc.getElementsByTagName("domain")
        val exact = mutableSetOf<String>(); val suffixes = mutableSetOf<String>()
        for (i in 0 until domains.length) {
            val d = domains.item(i) as Element
            if (d.getAttribute("includeSubdomains") == "true") suffixes += d.textContent.trim() else exact += d.textContent.trim()
        }
        assertEquals(UrlNormalizer.CLEARTEXT_HOSTS.toSet(), exact)
        assertEquals(UrlNormalizer.CLEARTEXT_SUFFIXES.toSet(), suffixes)
    }

    @Test fun manifestUsesTheConfig() =
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
}
