package io.github.pca.nexusbridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/** T1 acceptance: headless (no LAUNCHER), minimal permissions, signature-guarded port, a distinct applicationId, capabilities declared, and nothing extra. */
class NexusBridgeManifestTest {
    private val module = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }.resolve("nexus-bridge")
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(module.resolve("src/main/AndroidManifest.xml")).documentElement
    private val A = "http://schemas.android.com/apk/res/android"
    private fun NodeList.els() = (0 until length).map { item(it) as Element }
    private fun Element.name() = getAttributeNS(A, "name")

    @Test fun noComponentIsALauncherEntry() {
        val actions = doc.getElementsByTagName("action").els().map { it.name() }
        val cats = doc.getElementsByTagName("category").els().map { it.name() }
        assertFalse("android.intent.action.MAIN" in actions); assertFalse("android.intent.category.LAUNCHER" in cats); assertFalse("android.intent.category.LEANBACK_LAUNCHER" in cats)
        assertTrue(doc.getElementsByTagName("activity-alias").length == 0)
        assertEquals(listOf(".SettingsActivity"), doc.getElementsByTagName("activity").els().map { it.name() })
        assertEquals(0, doc.getElementsByTagName("activity").els().sumOf { it.getElementsByTagName("intent-filter").length })
    }

    @Test fun permissionsAreExactlyTheMinimalList() {
        assertEquals(
            listOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
            doc.getElementsByTagName("uses-permission").els().map { it.name() }.sorted(),
        )
    }

    @Test fun thePortIsGuardedByASignaturePermissionTheBridgeDefines() {
        val perm = doc.getElementsByTagName("permission").els().single()
        assertEquals("io.github.pca.nexusbridge.permission.BIND_NEXUS_PORT", perm.name())
        assertEquals("signature", perm.getAttributeNS(A, "protectionLevel"))
        val port = doc.getElementsByTagName("service").els().single { it.name() == ".NexusPortService" }
        assertEquals(perm.name(), port.getAttributeNS(A, "permission"))
        assertEquals(0, port.getElementsByTagName("intent-filter").length, "the port has no public intent filter")
    }

    @Test fun thePluginDeclaresTheCapabilitiesAndIsNotLaunchable() {
        val svc = doc.getElementsByTagName("service").els().single { it.name() == ".BridgeNexusService" }
        val meta = svc.getElementsByTagName("meta-data").els().associate { it.name() to it.getAttributeNS(A, "value") }
        assertEquals("surfaces,stt,tts,assistant", meta["com.anezium.rokidbus.plugin.CAPABILITIES"])
        assertEquals("3", meta["com.anezium.rokidbus.plugin.API_VERSION"])
        assertEquals("false", meta["com.anezium.rokidbus.plugin.LAUNCHABLE"])
        assertEquals("pca", meta["com.anezium.rokidbus.plugin.ID"])
        assertEquals(listOf("com.anezium.rokidbus.action.PLUGIN"), svc.getElementsByTagName("action").els().map { it.name() })
        // T6: the skills catalog is a resource reference (checked by SkillCatalogTest), and it is the ONLY metadata added since T1.
        val skills = svc.getElementsByTagName("meta-data").els().single { it.name() == "com.anezium.rokidbus.plugin.SKILLS" }
        assertEquals("@raw/nexus_skills", skills.getAttributeNS(A, "resource"))
    }

    @Test fun nothingIsBackedUpAndNoOtherComponents() {
        assertEquals("false", doc.getElementsByTagName("application").els().single().getAttributeNS(A, "allowBackup"))
        assertEquals(0, doc.getElementsByTagName("receiver").length); assertEquals(0, doc.getElementsByTagName("provider").length)
    }

    @Test fun theApplicationIdIsDistinctFromThePhoneApp() {
        val gradle = module.resolve("build.gradle.kts").readText()
        val id = Regex("""applicationId\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        assertEquals("io.github.pca.nexusbridge", id)
        val phone = File(module.parentFile!!, "phone/build.gradle.kts").readText()
        val phoneId = Regex("""applicationId\s*=\s*"([^"]+)"""").find(phone)!!.groupValues[1]
        assertTrue(id != phoneId && !id.startsWith("$phoneId."))
    }

    @Test fun theSdkIsPinnedToATagAndComesFromOneRestrictedRepository() {
        val gradle = module.resolve("build.gradle.kts").readText()
        assertTrue(Regex("""val nexusSdk = "sdk-v\d+\.\d+\.\d+"""").containsMatchIn(gradle))
        val settings = module.parentFile!!.resolve("settings.gradle.kts").readText()
        assertTrue(Regex("""jitpack\.io"\)\s*content \{ includeGroup\("com\.github\.Anezium\.Rokid-Nexus"\) \}""").containsMatchIn(settings))
    }
}
