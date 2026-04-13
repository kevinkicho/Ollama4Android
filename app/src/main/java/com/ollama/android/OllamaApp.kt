package com.ollama.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OllamaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val inferenceChannel = NotificationChannel(
                CHANNEL_INFERENCE,
                getString(R.string.notification_channel_inference),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when a model is loaded and running inference"
            }

            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                getString(R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows model download progress"
            }

            manager.createNotificationChannel(inferenceChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }

    companion object {
        const val CHANNEL_INFERENCE = "inference"
        const val CHANNEL_DOWNLOAD = "download"

        lateinit var instance: OllamaApp
            private set
    }
}
