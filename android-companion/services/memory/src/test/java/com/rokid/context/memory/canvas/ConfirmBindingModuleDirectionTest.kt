package com.rokid.context.memory.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Module-direction test (c8 #1): the shared 3b DTOs live in `:services:memory` and import nothing from orchestration or phone. */
class ConfirmBindingModuleDirectionTest {
    private fun moduleDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/canvas").isDirectory) d = d.parentFile
        return requireNotNull(d) { "services/memory module dir not found" }
    }

    @Test fun confirmBindingLivesInMemoryModuleWithNoOrchestrationOrPhoneImport() {
        val dir = File(moduleDir(), "src/main/java/com/rokid/context/memory/canvas")
        val names = dir.listFiles().orEmpty().map { it.name }
        for (n in listOf("ConfirmBinding.kt", "HelperFence.kt", "HelperJobState.kt")) assertTrue("$n missing in :services:memory", n in names)
        assertEquals("com.rokid.context.memory.canvas.ConfirmBinding", ConfirmBinding::class.java.name)
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }) {
            for (line in f.readLines()) {
                val t = line.trim()
                if (t.startsWith("import ") || t.startsWith("package ")) {
                    assertFalse("${f.name}: $t", t.contains("services.orchestration") || t.contains("context.phone") || t.startsWith("import com.rokid.context.canvas"))
                }
            }
        }
        val build = File(moduleDir(), "build.gradle.kts").readText()
        assertFalse(build.contains(":services:orchestration"))
        assertFalse(build.contains("project(\":phone\")"))
    }

    @Test fun bindingMessageIsTheFourFieldsInOrder() {
        assertEquals("job-1|abc|7|1700000120000", ConfirmBinding("job-1", "abc", 7, 1700000120000).message())
    }

    @Test fun stateMachineConfirmIsTheOnlyWayOutOfAwaitingConfirmToQueued() {
        for (from in HelperJobState.values()) for (e in JobEvent.values()) {
            val to = from.next(e)
            if (to == HelperJobState.QUEUED && from == HelperJobState.AWAITING_CONFIRM) assertEquals(JobEvent.CONFIRM, e)
        }
        assertEquals(HelperJobState.QUEUED, HelperJobState.AWAITING_CONFIRM.next(JobEvent.CONFIRM))
        assertEquals(null, HelperJobState.AWAITING_CONFIRM.next(JobEvent.LEASE))
        assertEquals(null, HelperJobState.QUEUED.next(JobEvent.CONFIRM))
        for (s in HelperJobState.values().filter { !it.open }) for (e in JobEvent.values()) assertEquals("$s $e", null, s.next(e))
    }
}
