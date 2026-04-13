package com.ollama.android.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Utility for checking and requesting Android system optimizations
 * that are required for stable LLM inference.
 */
object DeviceOptimization {

    /**
     * Check if the app is exempt from battery optimizations.
     * On Samsung, this returns true when battery is set to "Unrestricted".
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the app's battery settings page where user can choose Unrestricted.
     * On Samsung: Settings > Apps > [app] > Battery > Unrestricted
     * Uses the most direct deep-link available per manufacturer.
     */
    fun openAppBatteryPage(context: Context): Intent {
        if (isSamsungDevice()) {
            // Samsung One UI: try to go directly to per-app battery page
            // This opens: Settings > Apps > Ollama Android > Battery
            try {
                val intent = Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    return intent
                }
            } catch (_: Exception) { }
        }

        // Fallback: open app detail settings (works on all devices)
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Try direct battery optimization exemption dialog.
     * Some OEMs (Samsung) block this, so always have a fallback.
     */
    fun requestBatteryOptimizationExemption(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Check if Developer Options is enabled on the device.
     */
    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (_: Exception) { false }
    }

    /**
     * Check if "Don't keep activities" is turned ON (which is bad for us).
     * Returns true if the setting is ON (activities are being destroyed).
     * Returns false if OFF or Developer Options is disabled (safe).
     */
    fun isDontKeepActivitiesEnabled(context: Context): Boolean {
        if (!isDeveloperOptionsEnabled(context)) return false
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                "always_finish_activities", 0
            ) == 1
        } catch (_: Exception) { false }
    }

    /**
     * Open Developer Options settings.
     */
    fun openDeveloperSettings(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    /**
     * Open notification settings for the app.
     */
    fun openNotificationSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            openAppBatteryPage(context)
        }
    }

    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /**
     * Get device RAM in GB.
     */
    fun getDeviceRamGb(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Get available RAM in GB.
     */
    fun getAvailableRamGb(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
    }
}
