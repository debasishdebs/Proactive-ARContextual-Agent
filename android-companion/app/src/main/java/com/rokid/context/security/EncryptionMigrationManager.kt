package com.rokid.context.security

enum class MigrationState {
    IDLE,
    MIGRATING,
    RETRYING,
    ROLLED_BACK,
}

class EncryptionMigrationManager {
    fun nextState(failedAttempts: Int): MigrationState {
        return when {
            failedAttempts >= 2 -> MigrationState.ROLLED_BACK
            failedAttempts == 1 -> MigrationState.RETRYING
            else -> MigrationState.MIGRATING
        }
    }

    fun canReadLegacyUnencryptedData(migrationComplete: Boolean): Boolean = migrationComplete
}
