package com.ollama.android.ui.models

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ollama.android.data.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshModels()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Download models to run locally on your device. " +
                            "Models are downloaded from Hugging Face.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(uiState.models, key = { it.model.id }) { item ->
                ModelCard(
                    item = item,
                    onDownload = { viewModel.downloadModel(item.model.id) },
                    onDelete = { viewModel.deleteModel(item.model.id) }
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    item: ModelItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (item.model.recommended) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        item.model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(formatSize(item.model.sizeBytes)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Storage,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(32.dp)
                )
                AssistChip(
                    onClick = {},
                    label = { Text(item.model.ramRequired) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Memory,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(32.dp)
                )
                AssistChip(
                    onClick = {},
                    label = { Text(item.model.quantization) },
                    modifier = Modifier.height(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Download state / actions
            when (val state = item.downloadState) {
                is DownloadState.Idle -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download")
                    }
                }

                is DownloadState.Downloading -> {
                    Column {
                        @Suppress("DEPRECATION")
                        LinearProgressIndicator(
                            progress = state.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${formatSize(state.bytesDownloaded)} / ${formatSize(state.totalBytes)} " +
                                    "(${(state.progress * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is DownloadState.Completed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Downloaded",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }

                is DownloadState.Error -> {
                    Column {
                        Text(
                            "Error: ${state.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onDownload) {
                            Text("Retry Download")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${item.model.name}?") },
            text = { Text("This will remove the downloaded model file (${formatSize(item.model.sizeBytes)}).") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb > 1024) "%.1f GB".format(mb / 1024.0)
    else "%.0f MB".format(mb)
}
