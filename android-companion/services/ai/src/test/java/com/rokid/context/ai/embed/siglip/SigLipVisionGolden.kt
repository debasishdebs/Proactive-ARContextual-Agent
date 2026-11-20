package com.rokid.context.ai.embed.siglip

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Reads resources/siglip/siglip2_vision_golden.json (made by make_vision_golden.py from the HF SiglipImageProcessor + python onnxruntime). */
internal class VisionCase(val name: String, val w: Int, val h: Int, val srcRgb: ByteArray, val refU8Chw: ByteArray, val refEmbedding: FloatArray?) {
    /** Source image as packed ARGB (opaque). */
    fun argb(): IntArray = IntArray(w * h) { i ->
        (0xFF shl 24) or ((srcRgb[i * 3].toInt() and 0xFF) shl 16) or ((srcRgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (srcRgb[i * 3 + 2].toInt() and 0xFF)
    }

    /** The HF tensor, exactly: (u8/255 - 0.5)/0.5 per element, CHW. */
    fun refTensor(): FloatArray = FloatArray(refU8Chw.size) { SigLipPreprocess.norm(refU8Chw[it].toInt() and 0xFF) }

    companion object {
        fun load(): List<VisionCase> {
            val raw = VisionCase::class.java.getResourceAsStream("/siglip/siglip2_vision_golden.json")!!.readBytes().toString(Charsets.UTF_8)
            return Json.parseToJsonElement(raw).jsonObject["cases"]!!.jsonArray.map { e ->
                val o: JsonObject = e.jsonObject
                VisionCase(
                    o["name"]!!.jsonPrimitive.content, o["w"]!!.jsonPrimitive.int, o["h"]!!.jsonPrimitive.int,
                    gunzip(o["src_rgb_gz_b64"]!!.jsonPrimitive.content), gunzip(o["tensor_u8_chw_gz_b64"]!!.jsonPrimitive.content),
                    o["embedding_l2"]?.jsonArray?.map { it.jsonPrimitive.double.toFloat() }?.toFloatArray(),
                )
            }
        }

        private fun gunzip(b64: String): ByteArray = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(b64))).readBytes()
    }
}

internal fun cosine(a: FloatArray, b: FloatArray): Double {
    var d = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { d += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
    return d / (Math.sqrt(na) * Math.sqrt(nb))
}
