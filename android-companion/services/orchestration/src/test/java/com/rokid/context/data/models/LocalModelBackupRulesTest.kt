package com.rokid.context.data.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalModelBackupRulesTest {
    private val xmlDir = File("../../phone/src/main/res/xml")

    private fun excludesModels(section: Element): Boolean {
        val excludes = section.getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as Element }
            .any { it.getAttribute("domain") == "file" && it.getAttribute("path").trimEnd('/') == "models" }
    }

    private fun parse(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, name)).documentElement

    @Test fun modelsDirIsExcludedFromBackups() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("the rule's path is the real dir", File(ctx.filesDir, "models"), LocalModelStore.modelsDir(ctx))

        val extraction = parse("data_extraction_rules.xml")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = extraction.getElementsByTagName(tag).item(0) as Element
            assertTrue("$tag excludes models", excludesModels(section))
        }
        assertTrue("backup_rules excludes models", excludesModels(parse("backup_rules.xml")))
    }
}
