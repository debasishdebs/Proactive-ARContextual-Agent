package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BUG-R2-2 / D-D4 round 3: the native library can't load on the JVM, so the statements run against a fake that applies
 * the rules the device hit:
 * - sqlcipher-android 4.6.1's bind rule (SQLiteProgram: more bind args than `?` placeholders is "Too many bind arguments");
 * - a connection reads each database's schema when it opens (main) or ATTACHes it, and keeps that copy;
 * - `sqlcipher_export` (like VACUUM) creates the real tables in the target through the parser, so this connection
 *   knows them, but copies virtual tables (rootpage = 0, here the FTS4 `memory_fts`) and triggers as raw
 *   `sqlite_master` rows: they are on disk, yet this connection's schema copy doesn't have them, so
 *   `INSERT INTO <db>.memory_fts(memory_fts) VALUES('rebuild')` on the exporting connection is "no such table";
 * - the FTS special-command INSERT names the FTS table itself as its only column, and its external-content table
 *   (`memory`) must be in the same database.
 */
class SqlCipherConverterTest {
    /** One database file: its real tables and its raw-only (virtual) tables, as on disk. */
    private class Disk(val real: MutableSet<String> = mutableSetOf(), val virtual: MutableSet<String> = mutableSetOf())

    private class World {
        val cipher = Disk()
        val plain = Disk()
        val ran = mutableListOf<String>()
        var opened = 0
        var closed = 0
        var failOn: String? = null
        fun seed(d: Disk) { d.real += listOf("memory", "memory_fts_segments", "memory_fts_segdir", "memory_fts_docsize", "memory_fts_stat"); d.virtual += "memory_fts" }
    }

    private class FakeConn(private val w: World) : SqlCipherConverter.Sql {
        /** Per-alias schema copy, read when the database is opened or attached. */
        private val schema = mutableMapOf("main" to snapshot(w.cipher))
        private val disks = mutableMapOf("main" to w.cipher)
        init { w.opened++ }

        private fun snapshot(d: Disk) = (d.real + d.virtual).toMutableSet()

        private fun check(sql: String, args: Array<out Any>) {
            val n = sql.count { it == '?' }
            if (args.size > n) throw IllegalArgumentException("Too many bind arguments. ${args.size} arguments were provided but the statement needs $n arguments.")
            require(args.size == n) { "missing bind arguments" }
            args.forEach { require(it is String || it is Number || it is ByteArray) { "unsupported bind type" } }
            w.failOn?.let { if (sql.startsWith(it)) throw IllegalStateException("boom /data/user/0/secret") }
        }

        private fun apply(sql: String) {
            when {
                sql == "ATTACH DATABASE ? AS plain KEY ''" -> { disks["plain"] = w.plain; schema["plain"] = snapshot(w.plain) }
                sql == "DETACH DATABASE plain" -> { check(disks.remove("plain") != null) { "no such database: plain" }; schema.remove("plain") }
                sql.startsWith("SELECT sqlcipher_export(") -> {
                    val (to, from) = Regex("""sqlcipher_export\('(\w+)', '(\w+)'\)""").find(sql)!!.destructured
                    val src = disks.getValue(from); val dst = disks.getValue(to)
                    dst.real += src.real; dst.virtual += src.virtual
                    schema.getValue(to) += src.real // only what went through the parser
                }
                sql.startsWith("INSERT INTO ") && sql.endsWith("VALUES('rebuild')") -> {
                    val m = Regex("""INSERT INTO (\w+)\.(\w+)\((\w+)\) VALUES\('rebuild'\)""").matchEntire(sql) ?: error("not an FTS command: $sql")
                    val (db, table, column) = m.destructured
                    val known = schema[db] ?: throw IllegalStateException("unknown database $db")
                    if (table !in known) throw IllegalStateException("no such table: $db.$table")
                    require(column == table) { "the FTS command column must be the table name" }
                    if ("memory" !in known) throw IllegalStateException("no such table: $db.memory")
                }
                sql.startsWith("PRAGMA ") -> Unit
                else -> error("unexpected statement: $sql")
            }
        }

        override fun exec(sql: String, vararg args: Any) { check(sql, args); apply(sql); w.ran += sql }
        override fun queryInt(sql: String, vararg args: Any): Int { check(sql, args); apply(sql); w.ran += sql; return 7 }
        override fun query(sql: String, vararg args: Any) { check(sql, args); apply(sql); w.ran += sql }
        override fun close() { w.closed++ }
    }

    private fun world(toEncrypted: Boolean) = World().also { if (toEncrypted) it.seed(it.plain) else it.seed(it.cipher) }

    @Test fun toEncryptedRebuildsTheIndexOnAFreshConnection() {
        val w = world(toEncrypted = true)
        SqlCipherConverter.run({ FakeConn(w) }, "/x/context.db", toEncrypted = true)
        assertEquals(
            listOf(
                "ATTACH DATABASE ? AS plain KEY ''", "PRAGMA plain.user_version", "SELECT sqlcipher_export('main', 'plain')",
                "PRAGMA main.user_version = 7", "DETACH DATABASE plain",
                "INSERT INTO main.memory_fts(memory_fts) VALUES('rebuild')",
            ),
            w.ran,
        )
        assertEquals(2, w.opened); assertEquals(2, w.closed)
    }

    @Test fun toPlaintextRebuildsTheIndexInTheReattachedPlainDatabase() {
        val w = world(toEncrypted = false)
        SqlCipherConverter.run({ FakeConn(w) }, "/x/context.db.migrating", toEncrypted = false)
        assertEquals(
            listOf(
                "ATTACH DATABASE ? AS plain KEY ''", "PRAGMA main.user_version", "SELECT sqlcipher_export('plain', 'main')",
                "PRAGMA plain.user_version = 7", "DETACH DATABASE plain",
                "ATTACH DATABASE ? AS plain KEY ''", "INSERT INTO plain.memory_fts(memory_fts) VALUES('rebuild')", "DETACH DATABASE plain",
            ),
            w.ran,
        )
        assertEquals(2, w.opened); assertEquals(2, w.closed)
    }

    /** The round-3 failure, reproduced: the rebuild on the exporting connection can't see the copied FTS table. */
    @Test fun theFakeRejectsARebuildOnTheExportingConnection() {
        val w = world(toEncrypted = true)
        val c = FakeConn(w)
        c.exec("ATTACH DATABASE ? AS plain KEY ''", "/x/context.db")
        c.query("SELECT sqlcipher_export('main', 'plain')")
        try {
            c.exec("INSERT INTO main.memory_fts(memory_fts) VALUES('rebuild')")
            fail("expected no such table")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("no such table"))
        }
    }

    @Test fun aFailingStepIsReportedByItsReasonCodeWithNoMessage() {
        for ((prefix, reason) in listOf("SELECT sqlcipher_export" to EncryptionMigrator.FailReason.EXPORT, "INSERT INTO" to EncryptionMigrator.FailReason.REBUILD_INDEX)) {
            val w = world(toEncrypted = true).also { it.failOn = prefix }
            try {
                SqlCipherConverter.run({ FakeConn(w) }, "/x/context.db", toEncrypted = true)
                fail("expected a ConversionException")
            } catch (e: EncryptionMigrator.ConversionException) {
                assertEquals(reason, e.reason)
                assertNull(e.message)
                assertTrue(e.cause is IllegalStateException)
            }
            assertEquals("every connection opened is closed, also on failure", w.opened, w.closed)
        }
    }

    @Test fun aConnectionThatCantOpenIsReportedAsOpen() {
        try {
            SqlCipherConverter.run({ throw IllegalStateException("file is not a database") }, "/x/context.db", toEncrypted = true)
            fail("expected a ConversionException")
        } catch (e: EncryptionMigrator.ConversionException) {
            assertEquals(EncryptionMigrator.FailReason.OPEN, e.reason)
        }
    }
}
