package com.ollama.android.ui.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ollama.android.util.DeviceOptimization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isSamsung = remember { DeviceOptimization.isSamsungDevice() }
    val totalRam = remember { DeviceOptimization.getDeviceRamGb(context) }

    // ── Track states ────────────────────────────────────────────────────────
    var batteryExempt by remember { mutableStateOf(DeviceOptimization.isBatteryOptimizationExempt(context)) }
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    var dontKeepActivitiesOn by remember {
        mutableStateOf(DeviceOptimization.isDontKeepActivitiesEnabled(context))
    }
    var devOptionsEnabled by remember {
        mutableStateOf(DeviceOptimization.isDeveloperOptionsEnabled(context))
    }

    // Refresh when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = DeviceOptimization.isBatteryOptimizationExempt(context)
                notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                else true
                dontKeepActivitiesOn = DeviceOptimization.isDontKeepActivitiesEnabled(context)
                devOptionsEnabled = DeviceOptimization.isDeveloperOptionsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Launchers ───────────────────────────────────────────────────────────
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationGranted = granted }

    // Count completed steps (don't keep activities ON is a blocker)
    val allCriticalDone = batteryExempt && notificationGranted && !dontKeepActivitiesOn

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Hero Header ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Let's Get Ready",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "A few quick settings so your AI runs smoothly",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "%.1f GB RAM".format(totalRam),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Step 1: Battery Unrestricted ────────────────────────────
                GuidedStepCard(
                    stepNumber = 1,
                    title = if (isSamsung) "Set Battery to Unrestricted" else "Disable Battery Optimization",
                    description = if (isSamsung)
                        "Tap below to open battery settings, then choose \"Unrestricted\" " +
                                "so Android won't kill the app during AI inference."
                    else
                        "Allow the app to run without battery restrictions " +
                                "so Android won't kill it during AI inference.",
                    icon = Icons.Default.BatteryChargingFull,
                    isCompleted = batteryExempt,
                    isPulsing = !batteryExempt,
                    buttonText = if (isSamsung) "Open Battery Settings" else "Allow Unrestricted",
                    completedText = "Unrestricted",
                    onAction = {
                        try {
                            settingsLauncher.launch(
                                DeviceOptimization.openAppBatteryPage(context)
                            )
                        } catch (_: Exception) {
                            // Fallback: try direct dialog
                            try {
                                settingsLauncher.launch(
                                    DeviceOptimization.requestBatteryOptimizationExemption(context)
                                )
                            } catch (_: Exception) { }
                        }
                    },
                    hint = if (isSamsung && !batteryExempt)
                        "Choose \"Unrestricted\" on the next screen"
                    else null
                )

                // ── Step 2: Notifications ───────────────────────────────────
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    GuidedStepCard(
                        stepNumber = 2,
                        title = "Allow Notifications",
                        description = "Shows a small notification while the AI model is running. " +
                                "This keeps the app alive in the background.",
                        icon = Icons.Default.Notifications,
                        isCompleted = notificationGranted,
                        isPulsing = !notificationGranted && batteryExempt,
                        buttonText = "Grant Permission",
                        completedText = "Allowed",
                        onAction = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }

                // ── Step 3: Developer Options ───────────────────────────────
                // Show as critical (pulsing) if "Don't keep activities" is ON
                // Show as completed if dev options off OR setting is correctly off
                // Hide entirely if dev options not enabled (safe default)
                val step3Number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 3 else 2
                val step3Completed = !dontKeepActivitiesOn

                if (devOptionsEnabled) {
                    GuidedStepCard(
                        stepNumber = step3Number,
                        title = if (dontKeepActivitiesOn)
                            "Turn Off \"Don't Keep Activities\""
                        else
                            "Activities Stay Alive",
                        description = if (dontKeepActivitiesOn)
                            "\"Don't keep activities\" is currently ON in Developer Options. " +
                                    "This destroys the app whenever you switch away, killing AI inference. " +
                                    "Tap below to open Developer Options and turn it OFF."
                        else
                            "\"Don't keep activities\" is correctly turned OFF. " +
                                    "The app will stay alive in the background during inference.",
                        icon = Icons.Default.DeveloperMode,
                        isCompleted = step3Completed,
                        isPulsing = dontKeepActivitiesOn,
                        isOptional = !dontKeepActivitiesOn,
                        buttonText = "Open Developer Options",
                        completedText = "Activities Kept Alive",
                        onAction = {
                            try {
                                settingsLauncher.launch(DeviceOptimization.openDeveloperSettings())
                            } catch (_: Exception) { }
                        },
                        hint = if (dontKeepActivitiesOn)
                            "Scroll down and turn OFF \"Don't keep activities\""
                        else null
                    )
                }

                // ── Tips Card ───────────────────────────────────────────────
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Lightbulb, null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Performance Tips",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TipRow("Close other apps before loading a model")
                        TipRow("Gemma 3 1B needs ~1.5 GB RAM")
                        TipRow("First response is slower (model warmup)")
                        TipRow("Keep device plugged in for long sessions")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Continue Button ─────────────────────────────────────────
                val continueButtonPulse = if (allCriticalDone) {
                    rememberInfiniteTransition(label = "continue")
                        .animateFloat(
                            initialValue = 1f,
                            targetValue = 1.03f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = EaseInOutCubic),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse"
                        ).value
                } else 1f

                Button(
                    onClick = {
                        context.getSharedPreferences("ollama_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("setup_completed", true)
                            .apply()
                        onSetupComplete()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .scale(continueButtonPulse),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allCriticalDone)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (allCriticalDone) {
                        Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (allCriticalDone) "Continue to App" else "Skip for Now",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (allCriticalDone) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ── Guided Step Card ────────────────────────────────────────────────────────

@Composable
fun GuidedStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    icon: ImageVector,
    isCompleted: Boolean,
    isPulsing: Boolean,
    buttonText: String,
    completedText: String = "Done",
    isOptional: Boolean = false,
    hint: String? = null,
    onAction: () -> Unit
) {
    // Pulsing animation for the action button
    val pulseScale = if (isPulsing) {
        rememberInfiniteTransition(label = "step$stepNumber")
            .animateFloat(
                initialValue = 1f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse$stepNumber"
            ).value
    } else 1f

    // Glow animation for uncompleted critical steps
    val glowAlpha = if (isPulsing) {
        rememberInfiniteTransition(label = "glow$stepNumber")
            .animateFloat(
                initialValue = 0f,
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowAlpha$stepNumber"
            ).value
    } else 0f

    val cardColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            isOptional -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "cardColor$stepNumber"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(20.dp),
        border = if (isPulsing) BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f + glowAlpha)
        ) else if (isCompleted) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ) else null
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Animated step badge
                Surface(
                    shape = CircleShape,
                    color = if (isCompleted)
                        MaterialTheme.colorScheme.primary
                    else if (isOptional)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isCompleted) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Completed",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                "$stepNumber",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isOptional)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    if (isOptional) {
                        Text(
                            "OPTIONAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isCompleted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            // Hint text (e.g., "Choose Unrestricted on the next screen")
            if (hint != null && !isCompleted) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.TouchApp,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isCompleted) {
                // Completed state
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            completedText,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Pulsing action button
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .scale(pulseScale),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isPulsing) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) else ButtonDefaults.buttonColors(
                        containerColor = if (isOptional)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    ),
                    elevation = if (isPulsing) ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp
                    ) else ButtonDefaults.buttonElevation()
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        buttonText,
                        fontWeight = if (isPulsing) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun TipRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "\u2022",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
