package com.ollama.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ollama.android.MainActivity
import com.ollama.android.OllamaApp
import com.ollama.android.api.OllamaApiServer

/**
 * Foreground service that runs the Ollama-compatible HTTP API server.
 * Other apps on the device can connect to http://localhost:11434 to
 * use the loaded LLM for inference.
 */
class ApiServerService : Service() {

    private var server: OllamaApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, OllamaApiServer.DEFAULT_PORT)
            ?: OllamaApiServer.DEFAULT_PORT

        startForeground(NOTIFICATION_ID, createNotification(port))

        if (server == null) {
            try {
                server = OllamaApiServer(port).also { it.start() }
                Log.i(TAG, "API server started on port $port")
                isRunning = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start API server", e)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.shutdown()
        server = null
        isRunning = false
        Log.i(TAG, "API server stopped")
        super.onDestroy()
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
    }
}
