package com.rokid.context.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rokid.context.MainActivity
import com.rokid.context.R
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class RokidBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val prefs by lazy {
        getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
    }

    private var listenJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_LISTENING, null -> {
                return runCatching {
                    startInForeground("Listening for Rokid frames")
                    startListening()
                    START_STICKY
                }.getOrElse { error ->
                    Log.e(TAG, "Bridge start rejected", error)
                    setLastMessage("Bridge start failed: ${error.message ?: "unknown"}")
                    setListening(false)
                    stopSelf()
                    START_NOT_STICKY
                }
            }

            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        setListening(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listenJob != null) return

        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled || !hasBluetoothConnectPermission()) {
            updateNotification("Bluetooth unavailable for bridge")
            setListening(false)
            stopSelf()
            return
        }

        setListening(true)
        listenJob = scope.launch {
            try {
                val server = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                } catch (_: Exception) {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                }
                serverSocket = server
                while (isActive) {
                    val socket = server.accept() ?: continue
                    handleSocket(socket)
                }
            } catch (error: Exception) {
                if (isActive) {
                    Log.e(TAG, "Bridge listener stopped", error)
                    updateNotification("Bridge stopped: ${error.message ?: "unknown"}")
                    setLastMessage("Bridge error: ${error.message ?: "unknown"}")
                }
            } finally {
                cleanupServerSocket()
                setListening(false)
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        cleanupServerSocket()
        setListening(false)
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
            val type = payload["type"]?.jsonPrimitive?.content.orEmpty()
            when (type) {
                MESSAGE_TYPE_FRAME -> {
                    val received = persistFrame(payload)
                    if (received != null) {
                        val pendingConfig = pendingBridgeConfigForAck()
                        val deliveredConfig = pendingConfig != null
                        writeJson(
                            output,
                            ack(
                                success = true,
                                message = "Frame accepted: ${received.name}",
                                pendingConfig = pendingConfig,
                            ),
                        )
                        if (deliveredConfig) {
                            clearPendingSilencyMode()
                        }
                        val syncSuffix = if (deliveredConfig) " + config synced" else ""
                        setLastMessage("Frame accepted: ${received.name}$syncSuffix")
                        updateNotification("Received frame from Rokid")
                    } else {
                        writeJson(output, ack(success = false, message = "Invalid frame payload"))
                    }
                }

                MESSAGE_TYPE_PING -> {
                    val pendingConfig = pendingBridgeConfigForAck()
                    writeJson(
                        output,
                        ack(
                            success = true,
                            message = "bridge_alive",
                            pendingConfig = pendingConfig,
                        ),
                    )
                    if (pendingConfig != null) {
                        clearPendingSilencyMode()
                        setLastMessage("Glasses config sync delivered (ping ack)")
                    }
                }

                else -> {
                    writeJson(output, ack(success = false, message = "Unsupported type"))
                }
            }
        }
    }

    private fun persistFrame(payload: JsonObject): File? {
        val b64 = payload["image_b64"]?.jsonPrimitive?.content ?: return null
        val ext = payload["ext"]?.jsonPrimitive?.content?.lowercase(Locale.US).orEmpty().ifBlank { "jpg" }
        if (ext !in setOf("jpg", "jpeg", "png", "webp")) return null

        val frameId = payload["frame_id"]?.jsonPrimitive?.content.orEmpty().ifBlank { UUID.randomUUID().toString() }
        val safeFrameId = frameId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        if (!isUsableFrame(bytes)) {
            setLastMessage("Rejected frame: dark/blank payload guardrail")
            updateNotification("Rejected dark/blank frame")
            return null
        }

        val inbox = File(filesDir, "rokid_inbox")
        inbox.mkdirs()
        val out = File(inbox, "rokid_${System.currentTimeMillis()}_${safeFrameId}.$ext")
        out.writeBytes(bytes)

        val config = AppConfigStore.get(this)
        QueueRuntimeStore.enqueue(
            context = this,
            queue = QueuePriority.FRAME_INGEST,
            payload = QueuePayloads.frameIngest(source = "rokid_bridge"),
            personaSnapshot = config.persona,
            ttlHours = 24,
        )
        OrchestrationScheduler(this).enqueueQueueDrainNow()
        return out
    }

    private fun isUsableFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 512) return false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return false

            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            for (gy in 0 until 12) {
                val y = (gy * (height - 1)) / 11
                for (gx in 0 until 12) {
                    val x = (gx * (width - 1)) / 11
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val luma = (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
                    sum += luma
                    sumSq += luma * luma
                    count += 1
                }
            }
            if (count == 0) return false
            val mean = sum / count
            val variance = (sumSq / count) - (mean * mean)
            val std = kotlin.math.sqrt(variance.coerceAtLeast(0.0))
            return mean >= 6.0 && !(mean < 16.0 && std < 2.0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun readJson(input: DataInputStream): JsonObject {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Invalid bridge payload length: $length")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return Json.parseToJsonElement(String(body, Charsets.UTF_8)).let {
            it as? JsonObject ?: buildJsonObject { }
        }
    }

    private fun writeJson(output: DataOutputStream, payload: JsonObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun ack(success: Boolean, message: String, pendingConfig: JsonObject? = null): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("ack"))
            put("success", JsonPrimitive(success))
            put("message", JsonPrimitive(message))
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            if (pendingConfig != null) {
                put("config", pendingConfig)
            }
        }
    }

    private fun pendingBridgeConfigForAck(): JsonObject? {
        val mode = prefs.getString(KEY_PENDING_SILENCY_MODE, null)
            ?.trim()
            ?.lowercase(Locale.US)
            ?: return null
        val normalized = when (mode) {
            "stealth", "silent" -> "stealth"
            "compact", "max", "verbose_max" -> "compact"
            "hud", "medium", "verbose_medium" -> "hud"
            else -> "hud"
        }
        return buildJsonObject {
            put("silency_mode", JsonPrimitive(normalized))
            put("source", JsonPrimitive("companion"))
        }
    }

    private fun clearPendingSilencyMode() {
        prefs.edit()
            .remove(KEY_PENDING_SILENCY_MODE)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun startInForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, RokidBridgeService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rokid Bridge")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rokid Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Listens for frame payloads from Rokid glasses"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun setListening(listening: Boolean) {
        prefs.edit()
            .putBoolean(KEY_LISTENING, listening)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun setLastMessage(message: String) {
        prefs.edit()
            .putString(KEY_LAST_MESSAGE, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val TAG = "RokidBridgeService"
        private const val CHANNEL_ID = "rokid_bridge_channel"
        private const val NOTIFICATION_ID = 44011
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

        private const val PREF_BRIDGE = "rokid_bridge"
        private const val KEY_LISTENING = "listening"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_PENDING_SILENCY_MODE = "pending_silency_mode"

        const val ACTION_START_LISTENING = "com.rokid.context.bridge.START"
        const val ACTION_STOP_LISTENING = "com.rokid.context.bridge.STOP"

        private const val SERVICE_NAME = "RokidContextBridge"
        private const val MESSAGE_TYPE_FRAME = "rokid_frame"
        private const val MESSAGE_TYPE_PING = "ping"

        val APP_UUID: UUID = UUID.fromString("7f6f9a4f-4ba0-4d51-a6db-a00df9d23164")

        fun isListening(context: Context): Boolean {
            return context.getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(KEY_LISTENING, false)
        }

        fun lastMessage(context: Context): String {
            return context.getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MESSAGE, "Bridge idle")
                .orEmpty()
        }

        fun start(context: Context) {
            val intent = Intent(context, RokidBridgeService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RokidBridgeService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }

        fun queueSilencyModeSync(context: Context, mode: String) {
            val normalized = when (mode.trim().lowercase(Locale.US)) {
                "stealth", "silent" -> "stealth"
                "compact", "max", "verbose_max" -> "compact"
                "hud", "medium", "verbose_medium" -> "hud"
                else -> "hud"
            }
            context.getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_SILENCY_MODE, normalized)
                .putString(KEY_LAST_MESSAGE, "Queued glasses config sync: $normalized")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()
        }

        fun pushSilencyModeNow(context: Context, mode: String): RokidConfigPushClient.PushResult {
            val normalized = when (mode.trim().lowercase(Locale.US)) {
                "stealth", "silent" -> "stealth"
                "compact", "max", "verbose_max" -> "compact"
                "hud", "medium", "verbose_medium" -> "hud"
                else -> "hud"
            }
            val result = RokidConfigPushClient.pushSilencyMode(context, normalized)
            if (result.ok) {
                context.getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_PENDING_SILENCY_MODE)
                    .putString(KEY_LAST_MESSAGE, "Glasses config synced now: $normalized")
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
            } else {
                context.getSharedPreferences(PREF_BRIDGE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_SILENCY_MODE, normalized)
                    .putString(KEY_LAST_MESSAGE, "Immediate config sync failed: ${result.message}. Pending ack delivery.")
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
            }
            return result
        }
    }
}
