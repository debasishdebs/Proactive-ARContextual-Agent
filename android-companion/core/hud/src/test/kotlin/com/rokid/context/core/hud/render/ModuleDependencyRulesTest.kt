package com.rokid.context.core.hud.render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** R-01: `:core:hud` is foundation-only and app-agnostic; `:glasses` depends only on `:core:model` and `:core:hud`. */
class ModuleDependencyRulesTest {
    private fun gradle(path: String): String {
        // Gradle runs unit tests with the module directory as the working directory.
        val f = File(path).takeIf { it.exists() } ?: File("../../$path")
        return f.readText().lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
    }

    private fun projects(text: String) = Regex("""project\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] }.toSet()

    @Test fun hudDependsOnlyOnModelAndNoMaterial() {
        val hud = gradle("build.gradle.kts")
        assertEquals(setOf(":core:model"), projects(hud))
        assertFalse(hud.contains("material3"), ":core:hud must not use Material3")
    }

    @Test fun glassesDependsOnlyOnModelAndHud() {
        val glasses = gradle("../../glasses/build.gradle.kts")
        assertEquals(setOf(":core:model", ":core:hud"), projects(glasses))
        assertFalse(glasses.contains("material3"), ":glasses must not use Material3")
    }

    @Test fun hudSourcesHaveNoMaterialOrAppImports() {
        val bad = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.startsWith("import androidx.compose.material3") || it.startsWith("import com.rokid.context.glasses") || it.startsWith("import com.rokid.context.phone") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
