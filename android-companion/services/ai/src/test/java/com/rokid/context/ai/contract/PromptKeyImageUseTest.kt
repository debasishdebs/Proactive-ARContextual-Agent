package com.rokid.context.ai.contract

import com.rokid.context.ai.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Codex r10: every prompt key that receives a photo declares it ([PromptKey.imageUse]), so no new role can forget. The runtime guard (RoleRunner refuses
 * images on an [ImageUse.NONE] key) backs this up; this test fails at build time instead of at the first call.
 */
class PromptKeyImageUseTest {
    /** The production sources of the modules that build model calls (the test runs in services/ai). */
    private val roots = listOf("src/main", "../orchestration/src/main", "../../phone/src/main").map(::File).filter { it.isDirectory }

    /** Keys a call site sends images with: `RoleCall(PromptKey.X, ... images = ...)` within one call, and the test bed's photo inputs. */
    private fun keysCalledWithImages(): Set<PromptKey> {
        val found = mutableSetOf<PromptKey>()
        roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }.forEach { f ->
            val text = f.readText()
            Regex("RoleCall\\(\\s*PromptKey\\.([A-Z_]+)").findAll(text).forEach { m ->
                val call = callText(text, m.range.first + "RoleCall".length)
                if (Regex("\\bimages\\s*=").containsMatchIn(call)) found += PromptKey.valueOf(m.groupValues[1])
            }
            if (f.name == "TestBedInputs.kt") Regex("PromptKey\\.([A-Z_]+) ->([^\\n]*\\n){0,6}?[^\\n]*FrameInputs\\.fromFrames").findAll(text).forEach { found += PromptKey.valueOf(it.groupValues[1]) }
        }
        return found
    }

    private fun callText(s: String, open: Int): String {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) return s.substring(open, i + 1) } }
        }
        return s.substring(open)
    }

    @Test fun everyKeyThatReceivesImagesDeclaresIt() {
        assertTrue("source roots found", roots.size >= 2)
        val withImages = keysCalledWithImages()
        assertTrue("the scan sees the known photo calls: $withImages", withImages.containsAll(setOf(PromptKey.VISION_EXTRACT, PromptKey.CLUSTER_SUMMARY, PromptKey.INTENT_CLASSIFY, PromptKey.ANSWER)))
        for (key in PromptKey.entries) {
            if (key in withImages) assertTrue("$key is called with images but declares ImageUse.NONE", key.imageUse != ImageUse.NONE)
        }
    }

    /**
     * Codex r12: EVERY model call site (each `RoleCall(` and each `ModelRequest(` above the providers) declares its [com.rokid.context.model.PayloadSources]:
     * the photos and memories its prompt is built from, or `PayloadSources.NONE` when it carries no stored user content. A site that declares sources
     * (anything but NONE) also passes a `liveness =`, so the router re-checks them inside the permit. A new call site that forgets fails here.
     */
    @Test fun everyModelCallSiteDeclaresItsPayloadSources() {
        val missing = mutableListOf<String>()
        val unguarded = mutableListOf<String>()
        var seen = 0
        roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" && "/model/provider/" !in it.path.replace('\\', '/') }.toList() }.forEach { f ->
            val text = f.readText()
            Regex("\\b(RoleCall|ModelRequest)\\(").findAll(text).forEach { m ->
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (before.endsWith("class ") || before.endsWith("fun ")) return@forEach // the declarations themselves
                val call = callText(text, m.range.last)
                seen++
                val where = "${f.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
                val decl = Regex("\\bsources\\s*=\\s*([^,)\\n]*)").find(call)
                if (decl == null) { missing += where; return@forEach }
                if (!decl.groupValues[1].trim().startsWith("com.rokid.context.model.PayloadSources.NONE") && !decl.groupValues[1].trim().startsWith("PayloadSources.NONE") &&
                    !Regex("\\bliveness\\s*=").containsMatchIn(call)) unguarded += where
            }
        }
        assertTrue("the scan sees the model call sites: $seen", seen >= 15)
        assertEquals("model call sites without a PayloadSources declaration", emptyList<String>(), missing)
        assertEquals("call sites declaring sources without a liveness", emptyList<String>(), unguarded)
    }

    @Test fun everyKeyOfAVisionRoleRequiresItsPhoto() {
        // a key on a VISION role (VISION_EXTRACT, CLUSTER_SUMMARY, INTENT_CLASSIFY, and any new one) has the photo as its input
        for (key in PromptKey.entries.filter { it.role?.requiredCapability == Capability.VISION }) {
            assertTrue("$key runs on a vision role but is not marked requiresImage", key.requiresImage)
        }
        assertEquals(setOf(PromptKey.VISION_EXTRACT, PromptKey.CLUSTER_SUMMARY, PromptKey.INTENT_CLASSIFY), PromptKey.entries.filter { it.requiresImage }.toSet())
    }
}
