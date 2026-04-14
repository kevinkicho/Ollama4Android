package com.ollama.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ollama.android.MainActivity
import com.ollama.android.OllamaApp
import com.ollama.android.api.OllamaApiServer

/**
 * Foreground service that runs the Ollama-compatible HTTP API server.
 * Other apps on the device can connect to http://localhost:<port> to
 * use the loaded LLM for inference.
 *
 * Port can be configured in Settings. Set to 0 for OS-assigned port.
 */
class ApiServerService : Service() {

    private var server: OllamaApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedPort = intent?.getIntExtra(EXTRA_PORT, OllamaApiServer.DEFAULT_PORT)
            ?: OllamaApiServer.DEFAULT_PORT

        if (server == null) {
            try {
                server = OllamaApiServer(requestedPort).also { it.start() }
                val assignedPort = server!!.listeningPort
                activePort = assignedPort
                isRunning = true
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, createNotification(assignedPort))
                registerStopReceiver()
                Log.i(TAG, "API server started on port $assignedPort (requested: $requestedPort)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start API server", e)
                startForeground(NOTIFICATION_ID, createNotification(requestedPort))
                stopSelf()
            }
        } else {
            startForeground(NOTIFICATION_ID, createNotification(activePort))
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        server?.shutdown()
        server = null
        releaseWakeLock()
        isRunning = false
        activePort = 0
        Log.i(TAG, "API server stopped")
        super.onDestroy()
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(ACTION_STOP_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Ollama4Android::ApiServerWakeLock"
            ).apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotification(port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ApiServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OllamaApp.CHANNEL_INFERENCE)
            .setContentTitle("Ollama API Server")
            .setContentText("Running on http://localhost:$port")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Server", stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 2
        const val EXTRA_PORT = "port"
        const val ACTION_STOP = "com.ollama.android.STOP_API_SERVER"
        const val ACTION_STOP_BROADCAST = "com.ollama.android.STOP_API_BROADCAST"
        private const val TAG = "ApiServerService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activePort: Int = 0
            private set
    }
}
