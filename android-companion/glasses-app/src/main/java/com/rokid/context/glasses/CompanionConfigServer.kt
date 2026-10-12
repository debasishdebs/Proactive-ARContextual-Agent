package com.rokid.context.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class CompanionConfigServer(
    private val context: Context,
    private val onSilencyModeChanged: (SilencyMode) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private var listenJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (listenJob != null) return
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled || !hasConnectPermission()) {
            Log.w(TAG, "Companion config server skipped: bluetooth unavailable")
            return
        }
        listenJob = scope.launch {
            try {
                serverSocket = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                } catch (_: Exception) {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                }
                while (isActive) {
                    val socket = serverSocket?.accept() ?: continue
                    handleSocket(socket)
                }
            } catch (error: Exception) {
                if (isActive) {
                    Log.e(TAG, "Config server stopped", error)
                }
            } finally {
                cleanupServerSocket()
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        cleanupServerSocket()
        scope.cancel()
    }

    private fun cleanupServerSocket() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleSocket(socket: BluetoothSocket) {
        socket.use {
            val input = DataInputStream(it.inputStream)
            val output = DataOutputStream(it.outputStream)
            val payload = readJson(input)
            val type = payload.optString("type", "")
            if (type != MESSAGE_TYPE_COMPANION_CONFIG) {
                writeJson(output, ack(success = false, message = "Unsupported type"))
                return
            }
            val modeRaw = payload.optString("silency_mode", "").ifBlank { "hud" }
            val mode = SilencyMode.fromPersisted(modeRaw)
            GlassesSettingsStore.setSilencyMode(context, mode)
            onSilencyModeChanged(mode)
            writeJson(output, ack(success = true, message = "Applied silency mode: ${mode.persistedValue}"))
            Log.i(TAG, "Applied companion silency mode=${mode.persistedValue}")
        }
    }

    private fun readJson(input: DataInputStream): JSONObject {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Invalid control payload length: $length")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return JSONObject(String(body, Charsets.UTF_8))
    }

    private fun writeJson(output: DataOutputStream, payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun ack(success: Boolean, message: String): JSONObject {
        return JSONObject()
            .put("type", "ack")
            .put("success", success)
            .put("message", message)
            .put("ts", System.currentTimeMillis())
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "RokidConfigServer"
        private const val SERVICE_NAME = "RokidContextCompanionCfg"
        private const val MESSAGE_TYPE_COMPANION_CONFIG = "companion_config"
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
        private val APP_UUID = RokidBluetoothProtocol.APP_UUID
    }
}
