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
     * Codex r11: a call whose TEXT is derived from a photo (the photo's facts, caption or frame metadata) must name those photos
     * (`derivedFromFrames =`, or attach the images) and a `liveness =`, so the router's guard re-checks them before the text leaves.
     * Photo-derived = the call or its enclosing function (up to 30 lines before it) builds frame lines (`frameLine`, `photo.description`, `frame_id:`, `framesByIds`, photo cites).
     */
    @Test fun everyCallWithPhotoDerivedTextNamesItsPhotos() {
        val marker = Regex("frameLine|photo\\.description|frame_id:|framesByIds|\\.photos\\b|photoLines")
        val offenders = mutableListOf<String>()
        var seen = 0
        roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }.forEach { f ->
            val text = f.readText()
            Regex("RoleCall\\(\\s*PromptKey\\.([A-Z_]+)").findAll(text).forEach { m ->
                val call = callText(text, m.range.first + "RoleCall".length)
                val head = text.substring(0, m.range.first)
                val before = head.substring(maxOf(0, head.lastIndexOf(" fun "))).lines().takeLast(30).joinToString("\n") // the enclosing function only
                if (!marker.containsMatchIn(before + call)) return@forEach
                seen++
                val names = Regex("\\bimages\\s*=").containsMatchIn(call) || Regex("\\bderivedFromFrames\\s*=").containsMatchIn(call)
                if (!names || !Regex("\\bliveness\\s*=").containsMatchIn(call)) offenders += "${f.name}: ${m.groupValues[1]}"
            }
        }
        assertTrue("the scan sees the photo-derived calls (critics, ask)", seen >= 4)
        assertEquals("photo-derived calls without derivedFromFrames/images + liveness", emptyList<String>(), offenders)
    }

    @Test fun everyKeyOfAVisionRoleRequiresItsPhoto() {
        // a key on a VISION role (VISION_EXTRACT, CLUSTER_SUMMARY, INTENT_CLASSIFY, and any new one) has the photo as its input
        for (key in PromptKey.entries.filter { it.role?.requiredCapability == Capability.VISION }) {
            assertTrue("$key runs on a vision role but is not marked requiresImage", key.requiresImage)
        }
        assertEquals(setOf(PromptKey.VISION_EXTRACT, PromptKey.CLUSTER_SUMMARY, PromptKey.INTENT_CLASSIFY), PromptKey.entries.filter { it.requiresImage }.toSet())
    }
}
