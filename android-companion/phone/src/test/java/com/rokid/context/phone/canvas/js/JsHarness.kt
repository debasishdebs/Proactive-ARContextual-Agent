package com.rokid.context.phone.canvas.js

import java.io.File
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.io.IOAccess

/**
 * Runs the canvas ES-module JS on GraalJS (the bundle was never executed before this harness).
 * Roots resolve from the Gradle test working dir, which is the module dir (`phone/`).
 */
object JsHarness {
    data class Outcome(val name: String, val ok: Boolean, val message: String?)

    private val assetsRoot = File("src/main/assets/canvas").canonicalFile
    private val testsRoot = File("src/test/js").canonicalFile
    private const val MODULE = "application/javascript+module"

    /** Read-only file system that only sees the assets and tests roots. */
    private class RootedFs(private val roots: List<Path>) : FileSystem {
        private val d = FileSystem.newReadOnlyFileSystem(FileSystem.newDefaultFileSystem())
        private fun allowed(p: Path): Boolean {
            val n = p.toAbsolutePath().normalize()
            return roots.any { n.startsWith(it) }
        }
        private fun guard(p: Path): Path {
            if (!allowed(p)) throw SecurityException("path outside the JS roots")
            return p
        }
        override fun parsePath(uri: URI): Path = d.parsePath(uri)
        override fun parsePath(path: String): Path = d.parsePath(path)
        override fun checkAccess(path: Path, modes: MutableSet<out AccessMode>, vararg options: LinkOption) = d.checkAccess(guard(path), modes, *options)
        override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = throw SecurityException("read-only")
        override fun delete(path: Path) = throw SecurityException("read-only")
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel = d.newByteChannel(guard(path), options, *attrs)
        override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = d.newDirectoryStream(guard(dir), filter)
        override fun toAbsolutePath(path: Path): Path = d.toAbsolutePath(path)
        override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path = d.toRealPath(guard(path), *linkOptions)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> = d.readAttributes(guard(path), attributes, *options)
    }

    private fun newContext(): Context = Context.newBuilder("js")
        .allowIO(IOAccess.newBuilder().fileSystem(RootedFs(listOf(assetsRoot.toPath(), testsRoot.toPath()))).build())
        .allowHostAccess(org.graalvm.polyglot.HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowCreateProcess(false)
        .allowExperimentalOptions(true) // js.esm-eval-returns-exports is experimental in 23.1
        .option("js.esm-eval-returns-exports", "true")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    private fun module(f: File): Source = Source.newBuilder("js", f).mimeType(MODULE).build()

    // Runs every exported test (async ones awaited) and leaves a JSON array of [name, ok, message] in globalThis.__out.
    private const val RUNNER = """
        (function (ns) {
          globalThis.__out = null;
          (async function () {
            const out = [];
            const tests = ns.tests || {};
            for (const name of Object.keys(tests)) {
              try { await tests[name](); out.push([name, true, '']); }
              catch (e) { out.push([name, false, String((e && e.stack) || e)]); }
            }
            return out;
          })().then(
            (r) => { globalThis.__out = JSON.stringify(r); },
            (e) => { globalThis.__out = JSON.stringify([['<runner>', false, String(e)]]); });
        })
    """

    fun run(testFile: String): List<Outcome> = newContext().use { ctx ->
        val ns = try {
            ctx.eval(module(File(testsRoot, testFile)))
        } catch (e: PolyglotException) {
            return listOf(Outcome("<load $testFile>", false, e.message))
        }
        ctx.eval("js", RUNNER).execute(ns)
        val json = ctx.getBindings("js").getMember("__out")
        if (json == null || json.isNull) return listOf(Outcome("<run $testFile>", false, "tests did not settle (pending promise)"))
        val rows: JsonArray = Json.parseToJsonElement(json.asString()).jsonArray
        val found = rows.map {
            val r = it.jsonArray
            Outcome(r[0].jsonPrimitive.content, r[1].jsonPrimitive.boolean, r[2].jsonPrimitive.content.ifEmpty { null })
        }
        if (found.isEmpty()) listOf(Outcome("<run $testFile>", false, "no exported tests")) else found
    }

    fun parseError(assetFile: String): String? = newContext().use { ctx ->
        try {
            ctx.parse(module(File(assetsRoot, assetFile)))
            null
        } catch (e: PolyglotException) {
            if (e.isSyntaxError) (e.message ?: "syntax error") else throw e
        }
    }

    fun assertAllPass(testFile: String) {
        val results = run(testFile)
        val failed = results.filter { !it.ok }
        if (failed.isNotEmpty()) {
            throw AssertionError(failed.joinToString("\n") { "FAILED ${it.name}: ${it.message}" } + "\n(${results.size - failed.size}/${results.size} passed in $testFile)")
        }
    }
}
