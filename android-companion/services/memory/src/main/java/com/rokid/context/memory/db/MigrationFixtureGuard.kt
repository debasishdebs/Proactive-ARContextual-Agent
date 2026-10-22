package com.rokid.context.memory.db

/**
 * The safety gate of `SqlCipherMigrationDeviceTest`, as plain logic so a JVM test can prove it. [acquire] throws (and owns nothing) if the process is not
 * the androidTest APK or an unowned `context.db` exists; only after BOTH checks and the ownership marker does it set [owned]. [release] does nothing
 * without ownership: JUnit runs `@After` even when `@Before` threw, and a refused run must never delete the user's database files.
 */
class MigrationFixtureGuard(
    private val packageName: String,
    private val dbExists: () -> Boolean,
    private val markerExists: () -> Boolean,
    private val writeMarker: () -> Unit,
    private val deleteMarker: () -> Unit,
    private val cleanFixtures: () -> Unit,
) {
    var owned: Boolean = false
        private set

    fun acquire() {
        check(packageName.endsWith(".test")) { "refusing to run outside the androidTest APK sandbox" }
        check(!dbExists() || markerExists()) { "refusing to run: a context.db exists that this test did not create" }
        writeMarker()
        owned = true
        cleanFixtures()
    }

    fun release() {
        if (!owned) return
        cleanFixtures()
        deleteMarker()
        owned = false
    }
}
