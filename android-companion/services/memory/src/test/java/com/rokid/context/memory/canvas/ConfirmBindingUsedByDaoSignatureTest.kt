package com.rokid.context.memory.canvas

import com.rokid.context.memory.db.CanvasSlice3Dao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * c9 #3: the job DAO's confirm signature takes the memory-module [ConfirmBinding] (the plain data of c8 #1), and the DAO file imports nothing from orchestration or
 * phone (no upward dependency). WP1 owns the placement test; this one owns the signature.
 */
class ConfirmBindingUsedByDaoSignatureTest {
    @Test fun confirmJobFencedTakesTheMemoryModuleConfirmBinding() {
        val m = CanvasSlice3Dao::class.java.declaredMethods.filter { it.name == "confirmJobFenced" }
        assertEquals("exactly one confirmJobFenced", 1, m.size)
        val binding = m[0].parameterTypes.filter { it.simpleName == "ConfirmBinding" }
        assertEquals(1, binding.size)
        assertEquals("com.rokid.context.memory.canvas.ConfirmBinding", binding[0].name)
        assertTrue("the same class WP1's port uses", binding[0] == ConfirmBinding::class.java)
    }

    @Test fun theDaoFileImportsNothingFromOrchestrationOrPhone() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/CanvasSlice3Dao.kt").isFile) d = d.parentFile
        val text = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/CanvasSlice3Dao.kt").readText()
        val imports = text.lines().filter { it.trim().startsWith("import ") }
        assertTrue(imports.any { it.contains("com.rokid.context.memory.canvas.ConfirmBinding") })
        imports.forEach { assertFalse(it, it.contains("services.orchestration") || it.contains("context.phone") || it.contains("com.rokid.context.canvas.")) }
    }
}
