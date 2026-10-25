package com.rokid.context.phone.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Diagnostics export: cache/exports (the FileProvider folder), outside memory export's clean-up pattern (F22), no key-looking lines. */
class DiagnosticsExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesJobsAndLogsIntoExportsWithoutKeyLookingLines() = runBlocking {
        val f = DiagnosticsExport.write(tmp.root, listOf("1 TRUSTED job-1 frames=2 facts=1", "2 FAILED job-2 Authorization: Bearer sk-x"),
            listOf("I/RoleRunner: ANSWER v1 ok", "D/OkHttp: api-key: abc"))
        assertEquals("exports", f.parentFile!!.name)
        assertFalse(f.name.startsWith("proactive-context-memories-"))
        val text = f.readText()
        assertTrue(text, text.contains("job-1") && text.contains("ANSWER v1 ok"))
        assertFalse(text, text.contains("sk-x") || text.contains("abc"))
    }

    @Test fun theDiagnosticsExportIsRefusedWhileADeleteRuns() = kotlinx.coroutines.runBlocking {
        val err = runCatching { com.rokid.context.orchestration.DataOpGate.shared.delete { DiagnosticsExport.write(tmp.root, listOf("a"), listOf("b")) } }.exceptionOrNull()
        org.junit.Assert.assertTrue(err is com.rokid.context.orchestration.DataOpGate.DeleteRunning)
        org.junit.Assert.assertFalse(java.io.File(tmp.root, "exports/" + DiagnosticsExport.FILE_NAME).exists())
        org.junit.Assert.assertTrue(DiagnosticsExport.write(tmp.root, listOf("a"), listOf("b")).exists())
    }
}
