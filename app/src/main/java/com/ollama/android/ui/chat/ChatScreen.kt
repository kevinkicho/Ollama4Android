package com.ollama.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ollama.android.data.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showModelSelector by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshLocalModels()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ollama 4 Android", style = MaterialTheme.typography.titleMedium)
                        if (uiState.loadedModelName != null) {
                            Text(
                                uiState.loadedModelName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "No model loaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
                    // Stats display: tokens/s and memory
                    if (uiState.isModelLoaded) {
                        Column(
                            modifier = Modifier.padding(end = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            if (uiState.isGenerating) {
                                Text(
                                    "%.1f t/s".format(uiState.tokensPerSecond),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (uiState.memoryUsageMb > 0) {
                                Text(
                                    "${uiState.memoryUsageMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    IconButton(onClick = { showModelSelector = true }) {
                        Icon(Icons.Default.Memory, "Select model")
                    }
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear chat")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Error banner
            AnimatedVisibility(visible = uiState.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            uiState.error ?: "",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { viewModel.dismissError() }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
            }

            // Welcome message when no model loaded
            if (!uiState.isModelLoaded && uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Welcome to Ollama 4 Android",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Download a model in the Models tab,\nthen tap the chip icon to load it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showModelSelector = true }) {
                            Text("Load a Model")
                        }
                    }
                }
            } else {
                // Chat messages
                MessageList(
                    messages = uiState.messages,
                    isGenerating = uiState.isGenerating,
                    modifier = Modifier.weight(1f)
                )
            }

            // Input bar
            ChatInputBar(
                enabled = uiState.isModelLoaded && !uiState.isGenerating,
                isGenerating = uiState.isGenerating,
                onSend = { viewModel.sendMessage(it) },
                onStop = { viewModel.stopGeneration() }
            )
        }
    }

    // Clear chat confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Chat") },
            text = { Text("This will delete all messages in this conversation. The model will stay loaded.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChat()
                    showClearConfirm = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Model selector dialog
    if (showModelSelector) {
        ModelSelectorDialog(
            models = uiState.availableModels,
            currentModel = uiState.loadedModelName,
            onSelect = { model ->
                viewModel.loadModel(model)
                showModelSelector = false
            },
            onUnload = {
                viewModel.unloadModel()
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false }
        )
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    @Suppress("unused") isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll only when the last item is already visible (user is at/near bottom)
    // and user is not actively scrolling. This lets users scroll up freely during generation.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val atBottom = lastVisible != null &&
                    lastVisible.index >= info.totalItemsCount - 2
            if (atBottom && !listState.isScrollInProgress) {
                scope.launch {
                    listState.scrollToItem(messages.size)
                }
            }
        }
    }

    // When a new message is added (user sends or assistant starts), always scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        // Scroll anchor — lets us scroll past the last message's bottom edge
        item(key = "scroll_anchor") {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(bubbleColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .padding(12.dp)
            ) {
                if (message.content.isEmpty() && message.isStreaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Thinking...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (!isUser) {
                    MarkdownText(
                        text = message.content + if (message.isStreaming) "\u258C" else "",
                        codeBackground = MaterialTheme.colorScheme.surface
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                )
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    codeBackground: Color
) {
    val blocks = parseMarkdownBlocks(text)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            )
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    val annotated = parseInlineMarkdown(block.text)
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class CodeBlock(val code: String, val language: String = "") : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var i = 0
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
            paragraphLines.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
        } else {
            paragraphLines.add(line)
            i++
        }
    }
    flushParagraph()
    return blocks
}

@Composable
private fun parseInlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    val len = text.length

    while (i < len) {
        when {
            // Bold: **text**
            i + 1 < len && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // Italic: *text*
            text[i] == '*' && (i == 0 || text[i - 1] != '*') -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1 && (end + 1 >= len || text[end + 1] != '*')) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Inline code: `text`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x20000000)
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Heading markers: # at start of line
            (i == 0 || text[i - 1] == '\n') && text[i] == '#' -> {
                var hEnd = i
                while (hEnd < len && text[hEnd] == '#') hEnd++
                if (hEnd < len && text[hEnd] == ' ') {
                    val lineEnd = text.indexOf('\n', hEnd).let { if (it == -1) len else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                        append(text.substring(hEnd + 1, lineEnd))
                    }
                    i = lineEnd
                } else {
                    append(text[i])
                    i++
                }
            }
            // Bullet list: - at start of line
            (i == 0 || text[i - 1] == '\n') && (text[i] == '-' || text[i] == '\u2022') &&
                    i + 1 < len && text[i + 1] == ' ' -> {
                append("  \u2022 ")
                i += 2
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

@Composable
fun ChatInputBar(
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (!enabled && !isGenerating) "Load a model first..."
                        else "Type a message..."
                    )
                },
                enabled = enabled,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && enabled) {
                            onSend(text)
                            text = ""
                        }
                    }
                ),
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isGenerating) {
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, "Stop generation")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    enabled = enabled && text.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
fun ModelSelectorDialog(
    models: List<com.ollama.android.data.LocalModel>,
    currentModel: String?,
    onSelect: (com.ollama.android.data.LocalModel) -> Unit,
    onUnload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            if (models.isEmpty()) {
                Text("No models downloaded yet.\nGo to the Models tab to download one.")
            } else {
                Column {
                    models.forEach { model ->
                        val isCurrent = model.name == currentModel
                        Surface(
                            onClick = { onSelect(model) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        model.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        "${model.quantization} - ${formatSize(model.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.Check,
                                        "Current",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentModel != null) {
                TextButton(onClick = onUnload) {
                    Text("Unload Model")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb > 1024) "%.1f GB".format(mb / 1024.0)
    else "%.0f MB".format(mb)
}
