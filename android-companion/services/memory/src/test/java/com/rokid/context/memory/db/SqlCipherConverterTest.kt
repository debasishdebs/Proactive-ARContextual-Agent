package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BUG-R2-2: the native library can't load on the JVM, so the statements run against a fake that applies
 * sqlcipher-android 4.6.1's own bind rule (SQLiteProgram: more bind args than `?` placeholders is an
 * IllegalArgumentException, "Too many bind arguments"). Every statement must bind exactly its placeholders.
 */
class SqlCipherConverterTest {
    private class FakeSql(private val failOn: String? = null) : SqlCipherConverter.Sql {
        val ran = mutableListOf<String>()
        private fun check(sql: String, args: Array<out Any>) {
            val n = sql.count { it == '?' }
            if (args.size > n) throw IllegalArgumentException("Too many bind arguments. ${args.size} arguments were provided but the statement needs $n arguments.")
            require(args.size == n) { "missing bind arguments" }
            args.forEach { require(it is String || it is Number || it is ByteArray) { "unsupported bind type" } }
            if (failOn != null && sql.startsWith(failOn)) throw IllegalStateException("boom /data/user/0/secret")
            ran += sql
        }
        override fun exec(sql: String, vararg args: Any) = check(sql, args)
        override fun queryInt(sql: String, vararg args: Any): Int { check(sql, args); return 7 }
        override fun query(sql: String, vararg args: Any) = check(sql, args)
    }

    @Test fun toEncryptedRunsEveryStepWithExactlyItsBindArguments() {
        val sql = FakeSql()
        SqlCipherConverter.run(sql, "/x/context.db", toEncrypted = true)
        assertEquals(
            listOf(
                "ATTACH DATABASE ? AS plain KEY ''", "PRAGMA plain.user_version", "SELECT sqlcipher_export('main', 'plain')",
                "PRAGMA main.user_version = 7", "INSERT INTO main.memory_fts(memory_fts) VALUES('rebuild')", "DETACH DATABASE plain",
            ),
            sql.ran,
        )
    }

    @Test fun toPlaintextRunsEveryStepWithExactlyItsBindArguments() {
        val sql = FakeSql()
        SqlCipherConverter.run(sql, "/x/context.db.migrating", toEncrypted = false)
        assertEquals("SELECT sqlcipher_export('plain', 'main')", sql.ran[2])
        assertEquals("PRAGMA plain.user_version = 7", sql.ran[3])
        assertEquals(6, sql.ran.size)
    }

    @Test fun aFailingStepIsReportedByItsReasonCodeWithNoMessage() {
        try {
            SqlCipherConverter.run(FakeSql(failOn = "SELECT sqlcipher_export"), "/x/context.db", toEncrypted = true)
            fail("expected a ConversionException")
        } catch (e: EncryptionMigrator.ConversionException) {
            assertEquals(EncryptionMigrator.FailReason.EXPORT, e.reason)
            assertNull(e.message)
            assertTrue(e.cause is IllegalStateException)
        }
    }
}
