package com.ollama.android.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ollama.android.api.OllamaApiServer
import com.ollama.android.llama.LlamaAndroid
import com.ollama.android.service.ApiServerService
import com.ollama.android.util.DeviceOptimization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenSetup: () -> Unit = {}) {
    val context = LocalContext.current

    var nThreads by remember {
        mutableIntStateOf(
            Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        )
    }
    var nCtx by remember { mutableIntStateOf(2048) }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.9f) }
    var topK by remember { mutableIntStateOf(40) }
    var maxTokens by remember { mutableIntStateOf(512) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Device Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DeviceInfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    DeviceInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    DeviceInfoRow("CPU Cores", "${Runtime.getRuntime().availableProcessors()}")
                    DeviceInfoRow(
                        "Available RAM",
                        formatRam(context)
                    )
                    DeviceInfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

                    // llama.cpp system info
                    var systemInfo by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        try {
                            systemInfo = LlamaAndroid.instance.getSystemInfo()
                        } catch (_: Exception) { }
                    }
                    if (systemInfo != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "llama.cpp: $systemInfo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // API Server
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Local API Server",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Expose an Ollama-compatible API on localhost:${OllamaApiServer.DEFAULT_PORT} so other apps can use the loaded model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var apiRunning by remember { mutableStateOf(ApiServerService.isRunning) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("API Server", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (apiRunning) "Running on localhost:${OllamaApiServer.DEFAULT_PORT}"
                                else "Stopped",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (apiRunning)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = apiRunning,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val intent = Intent(context, ApiServerService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    apiRunning = true
                                } else {
                                    context.stopService(Intent(context, ApiServerService::class.java))
                                    apiRunning = false
                                }
                            }
                        )
                    }

                    if (apiRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Endpoints:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "POST /api/chat\nPOST /api/generate\nGET  /api/tags\nGET  /api/version",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Base URL: http://localhost:${OllamaApiServer.DEFAULT_PORT}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Inference Settings
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Inference Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSlider(
                        label = "Threads",
                        value = nThreads.toFloat(),
                        onValueChange = { nThreads = it.toInt() },
                        valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat(),
                        steps = Runtime.getRuntime().availableProcessors() - 2,
                        displayValue = "$nThreads"
                    )

                    SettingSlider(
                        label = "Context Length",
                        value = nCtx.toFloat(),
                        onValueChange = { nCtx = it.toInt() },
                        valueRange = 512f..4096f,
                        steps = 6,
                        displayValue = "$nCtx"
                    )

                    SettingSlider(
                        label = "Max Tokens",
                        value = maxTokens.toFloat(),
                        onValueChange = { maxTokens = it.toInt() },
                        valueRange = 64f..2048f,
                        steps = 14,
                        displayValue = "$maxTokens"
                    )
                }
            }

            // Sampling Settings
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Sampling",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSlider(
                        label = "Temperature",
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        displayValue = "%.2f".format(temperature)
                    )

                    SettingSlider(
                        label = "Top P",
                        value = topP,
                        onValueChange = { topP = it },
                        valueRange = 0f..1f,
                        displayValue = "%.2f".format(topP)
                    )

                    SettingSlider(
                        label = "Top K",
                        value = topK.toFloat(),
                        onValueChange = { topK = it.toInt() },
                        valueRange = 1f..100f,
                        displayValue = "$topK"
                    )
                }
            }

            // Device Optimization
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Device Optimization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val batteryExempt = DeviceOptimization.isBatteryOptimizationExempt(context)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Battery Optimization", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (batteryExempt) "Unrestricted" else "Restricted",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (batteryExempt)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenSetup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Device Setup Again")
                    }
                }
            }

            // About
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "About",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Ollama 4 Android v1.0.0",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Powered by llama.cpp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Run LLMs locally on your Android device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    displayValue: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayValue,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatRam(context: Context): String {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
    return "%.1f GB / %.1f GB".format(availGb, totalGb)
}
