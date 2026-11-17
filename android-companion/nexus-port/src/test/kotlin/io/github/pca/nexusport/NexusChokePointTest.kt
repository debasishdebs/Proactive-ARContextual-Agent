package io.github.pca.nexusport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Inventory (pattern of ProvenanceChokePointTest): NexusHudSink is the ONLY path from PCA to Nexus. Fails when any main source anywhere in the repository
 *  - reaches the AIDL port (`NexusPort`, `NexusPort.Stub`, `INexusPort`) or the transport outside the sink's own file and the AIDL adapter;
 *  - imports the Nexus SDK (`com.anezium.rokidbus`) outside the bridge APK's Kotlin sources;
 *  - names the bridge package or `io.github.pca.nexusport` from a module that is not one of the two Nexus modules or the phone's single reviewed provider (none yet).
 */
class NexusChokePointTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun mainFiles() = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" && it.name != ".gradle" }
        .filter { it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }.toList()
    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    /** Files allowed to touch the port or the transport. Nothing else. */
    private val PORT_USERS = setOf(
        "nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt",
        "nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusTransport.kt",       // the AIDL adapter (internal)
        "nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/NexusPortService.kt", // the SERVER side of the port
    )
    private val PORT = Regex("""\b(NexusPort\b|NexusPort\.Stub|INexusPort|NexusPortCallback|AidlNexusTransport|NexusTransport\b)""")
    private val SDK = Regex("""com\.anezium\.rokidbus""")

    @Test fun onlyTheSinkTouchesThePortAndTheTransport() {
        val offenders = mainFiles().filter { rel(it) !in PORT_USERS }.filter { PORT.containsMatchIn(stripComments(it.readText())) }.map(::rel)
        assertEquals(emptyList<String>(), offenders, "a path to Nexus that bypasses NexusHudSink")
    }

    @Test fun theSinkIsTheOnlyCallerOfTheTransportMethods() {
        val sink = File(root, "nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt").readText()
        // the sink must call the transport; no other file in this module declares a caller (they only implement or declare it)
        assertTrue(Regex("""transport\.(show|hide|speak|stopSpeech|forgetHistory|setFeatures)\(""").containsMatchIn(sink))
        val other = mainFiles().filter { rel(it).startsWith("nexus-port/") && it.name != "NexusHudSink.kt" }
            .filter { Regex("""\btransport\.(show|hide|speak|stopSpeech|forgetHistory|setFeatures)\(""").containsMatchIn(stripComments(it.readText())) }.map(::rel)
        assertEquals(emptyList<String>(), other)
    }

    @Test fun theTransportAndTheSinkConstructorAreInternal() {
        val t = File(root, "nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusTransport.kt").readText()
        assertTrue(Regex("""(?m)^internal interface NexusTransport""").containsMatchIn(t))
        assertTrue(Regex("""(?m)^internal class AidlNexusTransport""").containsMatchIn(t))
        assertTrue("class NexusHudSink internal constructor" in File(root, "nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt").readText())
    }

    @Test fun theNexusSdkLivesOnlyInTheBridge() {
        val offenders = mainFiles().filter { SDK.containsMatchIn(stripComments(it.readText())) }.map(::rel).filterNot { it.startsWith("nexus-bridge/src/main/kotlin/") }
        assertEquals(emptyList<String>(), offenders)
        // and the SDK appears in no other module's build file
        val builds = root.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }.filter { it.name == "build.gradle.kts" && "Rokid-Nexus" in it.readText() }.map(::rel).toList()
        assertEquals(listOf("nexus-bridge/build.gradle.kts"), builds)
    }

    @Test fun noOtherModuleNamesTheNexusPackagesYet() {
        // The phone-side provider (T7/T8) will add exactly one reviewed file here; until then no module outside the two Nexus modules may mention them.
        val offenders = mainFiles().filter { !rel(it).startsWith("nexus-port/") && !rel(it).startsWith("nexus-bridge/") }
            .filter { Regex("""io\.github\.pca\.nexus""").containsMatchIn(stripComments(it.readText())) }.map(::rel)
        assertEquals(emptyList<String>(), offenders)
    }

    private fun stripComments(s: String): String = s.replace(Regex("""(?s)/\*.*?\*/"""), "").lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
}
