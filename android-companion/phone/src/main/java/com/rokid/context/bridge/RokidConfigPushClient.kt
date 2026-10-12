package com.rokid.context.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Locale

object RokidConfigPushClient {
    private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    data class PushResult(
        val ok: Boolean,
        val message: String,
    )

    @SuppressLint("MissingPermission")
    fun pushSilencyMode(context: Context, silencyMode: String): PushResult {
        val mode = normalizeSilencyMode(silencyMode)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return PushResult(false, "Bluetooth adapter unavailable")
        if (!adapter.isEnabled) return PushResult(false, "Bluetooth disabled")
        if (!hasConnectPermission(context)) return PushResult(false, "Bluetooth permission missing")

        val target = bestGlassesCandidate(adapter.bondedDevices.toList())
            ?: return PushResult(false, "No paired Rokid glasses found")

        runCatching { adapter.cancelDiscovery() }
        val socket = runCatching { connectSocket(target) }
            .getOrElse { return PushResult(false, "Connect failed: ${it.message}") }

        return socket.use {
            val output = DataOutputStream(it.outputStream)
            val input = DataInputStream(it.inputStream)
            val payload = buildJsonObject {
                put("type", JsonPrimitive("companion_config"))
                put("silency_mode", JsonPrimitive(mode))
                put("ts", JsonPrimitive(System.currentTimeMillis()))
            }
            writeJson(output, payload)
            val ack = readJson(input)
            val success = ack["success"]?.toString()?.contains("true", ignoreCase = true) == true
            val message = ack["message"]?.toString()?.trim('"') ?: "ack"
            PushResult(success, message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestGlassesCandidate(devices: List<BluetoothDevice>): BluetoothDevice? {
        return devices
            .sortedByDescending { it.glassesCandidateScore() }
            .firstOrNull { it.glassesCandidateScore() > -100 }
            ?: devices.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.glassesCandidateScore(): Int {
        val name = (name ?: address).lowercase(Locale.US)
        var score = when (bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.WEARABLE -> 90
            BluetoothClass.Device.Major.PERIPHERAL -> 30
            BluetoothClass.Device.Major.PHONE -> -100
            else -> 0
        }
        if (name.contains("rokid") || name.contains("glass")) score += 80
        if (name.contains("phone") || name.contains("samsung") || name.contains("pixel")) score -= 80
        return score
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val strategies = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(RokidBridgeService.APP_UUID) },
            { device.createRfcommSocketToServiceRecord(RokidBridgeService.APP_UUID) },
            {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (strategy in strategies) {
            val socket = try {
                strategy()
            } catch (error: Exception) {
                lastError = error
                continue
            }
            try {
                socket.connect()
                return socket
            } catch (error: Exception) {
                lastError = error
                runCatching { socket.close() }
            }
        }
        throw IOException(lastError?.message ?: "Unable to connect to glasses")
    }

    private fun writeJson(output: DataOutputStream, payload: JsonObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun readJson(input: DataInputStream): JsonObject {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Invalid control message length: $length")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject ?: buildJsonObject { }
    }

    private fun hasConnectPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizeSilencyMode(mode: String): String {
        return when (mode.trim().lowercase()) {
            "stealth", "silent" -> "stealth"
            "compact", "max", "verbose_max" -> "compact"
            "hud", "medium", "verbose_medium" -> "hud"
            else -> "hud"
        }
    }
}
