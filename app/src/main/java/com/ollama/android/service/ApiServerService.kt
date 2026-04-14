package com.ollama.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        // Service survives app swipe-away — do nothing, keep running
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        server?.shutdown()
        server = null
        releaseWakeLock()
        isRunning = false
        activePort = 0
        Log.i(TAG, "API server stopped")
        super.onDestroy()
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
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OllamaApp.CHANNEL_INFERENCE)
            .setContentTitle("Ollama API Server")
            .setContentText("Running on localhost:$port")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 2
        const val EXTRA_PORT = "port"
        private const val TAG = "ApiServerService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activePort: Int = 0
            private set
    }
}
