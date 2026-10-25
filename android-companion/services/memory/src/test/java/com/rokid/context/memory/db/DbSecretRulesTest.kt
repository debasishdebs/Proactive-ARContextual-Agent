package com.rokid.context.memory.db

import com.rokid.context.memory.DbEncryption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The SQLCipher passphrase lives only in the Keystore-backed DbKeyStore: never logged, never in UI-facing state. */
class DbSecretRulesTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun noLogCallMentionsThePassphraseOrKey() {
        assertTrue("memory sources not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val logLine = Regex("""\b(?:Pc)?Log\.[vdiwe]\(|SafeLog\.|println\(""")
        val secret = Regex("""passphrase|key\(\)|\bkey\b""", RegexOption.IGNORE_CASE)
        val bad = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (logLine.containsMatchIn(l) && secret.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun theStatusShownToSettingsCarriesNoKeyMaterial() {
        val fields = DbEncryption.Status::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name to it.type }.toSet()
        assertEquals(setOf("encrypted" to Boolean::class.javaObjectType, "pendingTarget" to Boolean::class.javaObjectType, "lastChangeFailed" to Boolean::class.javaPrimitiveType), fields)
    }
}
