package com.rokid.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R-04: no raw RFCOMM/SPP code is left in either app (spec §1.4); frames travel only over CXR. */
class RfcommGoneTest {
    private val roots = listOf(File("src/main"), File("../glasses/src/main"))
    private val banned = listOf(
        "BluetoothSocket", "BluetoothServerSocket", "createRfcommSocket", "createInsecureRfcommSocket",
        "listenUsingRfcomm", "listenUsingInsecureRfcomm", "RokidBridgeService", "RokidConfigPushClient",
        "CompanionConfigServer", "RokidBluetoothProtocol",
    )

    @Test fun bothSourceTreesAreScanned() = roots.forEach { assertTrue(it.path, it.isDirectory) }

    @Test fun noRfcommCodeRemains() {
        val hits = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList() }
            .flatMap { f -> banned.filter { it in f.readText() }.map { "${f.path}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }
}
