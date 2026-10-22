package com.rokid.context.memory.forget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S58: `:services:memory` defines the ForgetListener seam and never depends on the layers that implement it. */
class MemoryHasNoOrchestrationDependencyTest {
    private val module: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "build.gradle.kts").exists() && File(it, "src/main").isDirectory }

    @Test fun gradleDeclaresNoOrchestrationPhoneConnectorsOrVoiceProject() {
        val gradle = File(module, "build.gradle.kts").readText()
        listOf(":services:orchestration", ":phone", ":services:connectors", ":services:voice", ":services:glasses-link").forEach { assertTrue("memory must not depend on $it", "project(\"$it\")" !in gradle) }
    }

    @Test fun noMainSourceImportsThem() {
        val bad = module.resolve("src/main").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> Regex("""^import com\.rokid\.context\.(orchestration|canvas|phone|connectors\.|voice|link)""", RegexOption.MULTILINE).containsMatchIn(f.readText()) }.map { it.name }.toList()
        assertTrue("memory imports a higher layer: $bad", bad.isEmpty())
    }
}
