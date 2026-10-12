package com.rokid.context.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionMigrationTest {
    private val manager = EncryptionMigrationManager()
    private val exportManager = DiagnosticExportManager()

    @Test
    fun encryptionMigrationRetriesOnceThenRollsBack() {
        assertEquals(MigrationState.MIGRATING, manager.nextState(failedAttempts = 0))
        assertEquals(MigrationState.RETRYING, manager.nextState(failedAttempts = 1))
        assertEquals(MigrationState.ROLLED_BACK, manager.nextState(failedAttempts = 2))
    }

    @Test
    fun unmigratedDataBlockedFromReadPipelines() {
        assertFalse(manager.canReadLegacyUnencryptedData(migrationComplete = false))
        assertTrue(manager.canReadLegacyUnencryptedData(migrationComplete = true))
    }

    @Test
    fun decryptTokenSingleUseAndExpiresInTenMinutes() {
        assertTrue(exportManager.tokenValid(used = false, ageMs = 9 * 60 * 1000L))
        assertFalse(exportManager.tokenValid(used = true, ageMs = 1000L))
        assertFalse(exportManager.tokenValid(used = false, ageMs = 11 * 60 * 1000L))
    }

    @Test
    fun decryptedArtifactsDeletedImmediatelyAfterShareOrAfterFiveMinutes() {
        assertTrue(exportManager.shouldDeleteDecryptedArtifact(shared = true, sessionOpenMs = 1_000L))
        assertFalse(exportManager.shouldDeleteDecryptedArtifact(shared = false, sessionOpenMs = 4 * 60 * 1000L))
        assertTrue(exportManager.shouldDeleteDecryptedArtifact(shared = false, sessionOpenMs = 5 * 60 * 1000L))
    }
}
