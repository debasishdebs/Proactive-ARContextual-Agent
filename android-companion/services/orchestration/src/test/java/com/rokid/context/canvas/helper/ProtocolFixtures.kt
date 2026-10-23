package com.rokid.context.canvas.helper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Reads the seven json files of tools/canvas-helper/protocol, the same files the Python tests read (tools/canvas-helper/tests). */
object ProtocolFixtures {
    val SEVEN = listOf(
        "confirm-vectors.json", "constants.json", "hash-vectors.json", "job-id.json",
        "py_exec-payload-samples.json", "py_exec-result-samples.json", "schema-subset-rows.json",
    )

    val dir: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "tools/canvas-helper/protocol").isDirectory) d = d.parentFile
        File(requireNotNull(d) { "tools/canvas-helper/protocol not found above ${System.getProperty("user.dir")}" }, "tools/canvas-helper/protocol")
    }

    fun load(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8)).jsonObject
    fun JsonObject.arr(k: String): JsonArray = getValue(k).jsonArray
    fun JsonObject.str(k: String): String = getValue(k).jsonPrimitive.content
    fun JsonObject.num(k: String): Long = getValue(k).jsonPrimitive.long
    fun JsonObject.obj(k: String): JsonObject = getValue(k).jsonObject
    fun JsonElement.o(): JsonObject = jsonObject
    fun JsonObject.i(k: String): Int = getValue(k).jsonPrimitive.int
}
