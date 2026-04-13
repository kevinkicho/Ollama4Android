package com.ollama.android.ui.chat

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollama.android.data.ChatMessage
import com.ollama.android.data.LocalModel
import com.ollama.android.data.ModelRepository
import com.ollama.android.llama.LlamaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadedModelName: String? = null,
    val availableModels: List<LocalModel> = emptyList(),
    val error: String? = null,
    val tokensPerSecond: Float = 0f,
    val memoryUsageMb: Int = 0,
    val memoryLimitMb: Int = 0
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llama = LlamaAndroid.instance
    private val modelRepo = ModelRepository.getInstance(application)
    private val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    // Context window size — balance between conversation length and memory
    private val contextSize = 2048

    init {
        refreshLocalModels()
        updateMemoryUsage()
    }

    private fun updateMemoryUsage() {
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt()
        val javaHeapMb = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).toInt()
        val totalUsedMb = nativeHeapMb + javaHeapMb

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val deviceTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt()

        _uiState.update { it.copy(memoryUsageMb = totalUsedMb, memoryLimitMb = deviceTotalMb) }
    }

    fun refreshLocalModels() {
        viewModelScope.launch {
            val models = modelRepo.getLocalModels()
            _uiState.update { it.copy(availableModels = models) }
        }
    }

    fun loadModel(model: LocalModel) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(error = null) }
                llama.loadModel(model.filePath, nGpuLayers = 0)
                llama.createContext(nCtx = contextSize, nThreads = 4)
                _uiState.update {
                    it.copy(
                        isModelLoaded = true,
                        loadedModelName = model.name
                    )
                }
                updateMemoryUsage()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Failed to load model: ${e.message}",
                        isModelLoaded = false,
                        loadedModelName = null
                    )
                }
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            stopGeneration()
            llama.unload()
            _uiState.update {
                it.copy(isModelLoaded = false, loadedModelName = null)
            }
            updateMemoryUsage()
        }
    }

    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank() || _uiState.value.isGenerating) return

        val userMsg = ChatMessage(role = ChatMessage.Role.USER, content = userMessage)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                error = null
            )
        }

        generateResponse()
    }

    private fun generateResponse() {
        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val assistantMsg = ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true
            )

            _uiState.update {
                it.copy(
                    messages = it.messages + assistantMsg,
                    isGenerating = true
                )
            }

            try {
                val prompt = buildPrompt()
                val startTime = System.currentTimeMillis()
                var tokenCount = 0
                val responseBuilder = StringBuilder()

                llama.complete(prompt, nPredict = 1024).collect { token ->
                    responseBuilder.append(token)
                    tokenCount++

                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val tps = if (elapsed > 0) tokenCount / elapsed else 0f

                    _uiState.update { state ->
                        val messages = state.messages.toMutableList()
                        messages[messages.lastIndex] = assistantMsg.copy(
                            content = responseBuilder.toString(),
                            isStreaming = true
                        )
                        state.copy(messages = messages, tokensPerSecond = tps)
                    }

                    // Update memory usage periodically (every 50 tokens)
                    if (tokenCount % 50 == 0) updateMemoryUsage()
                }

                // Mark as complete
                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    messages[messages.lastIndex] = messages.last().copy(isStreaming = false)
                    state.copy(
                        messages = messages,
                        isGenerating = false
                    )
                }
                updateMemoryUsage()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = "Generation error: ${e.message}"
                    )
                }
            }
        }
    }

    private fun buildPrompt(): String {
        val modelName = _uiState.value.loadedModelName?.lowercase() ?: ""

        // Get all messages, but trim old ones if conversation is too long
        val messages = trimMessagesToFit(_uiState.value.messages)

        return when {
            modelName.contains("gemma") -> buildGemmaPrompt(messages)
            modelName.contains("qwen") -> buildChatMLPrompt(messages)
            modelName.contains("smollm") -> buildChatMLPrompt(messages)
            else -> buildChatMLPrompt(messages) // ChatML is most widely compatible
        }
    }

    /**
     * Trim older messages to keep the prompt within context window.
     * Always keeps the first system message (if any) and the most recent messages.
     * Rough estimate: ~4 characters per token for English text.
     */
    private fun trimMessagesToFit(messages: List<ChatMessage>): List<ChatMessage> {
        // Reserve tokens: ~200 for formatting overhead, rest for generation
        val maxPromptTokens = contextSize - 400
        val maxPromptChars = maxPromptTokens * 4

        // Try with all messages first
        var totalChars = messages.sumOf { it.content.length + 30 } // 30 for format tags
        if (totalChars <= maxPromptChars) return messages

        // Trim from the beginning, keeping the last N messages
        val trimmed = messages.toMutableList()
        while (trimmed.size > 2 && totalChars > maxPromptChars) {
            val removed = trimmed.removeAt(0)
            totalChars -= (removed.content.length + 30)
        }
        return trimmed
    }

    private fun buildGemmaPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> {
                    sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                }
                ChatMessage.Role.USER -> {
                    sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (msg.content.isNotEmpty()) {
                        sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                    }
                }
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildChatMLPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> {
                    sb.append("<|im_start|>system\n${msg.content}<|im_end|>\n")
                }
                ChatMessage.Role.USER -> {
                    sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (msg.content.isNotEmpty()) {
                        sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
                    }
                }
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun stopGeneration() {
        llama.abort()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            if (messages.isNotEmpty() && messages.last().isStreaming) {
                messages[messages.lastIndex] = messages.last().copy(isStreaming = false)
            }
            state.copy(isGenerating = false, messages = messages)
        }
    }

    fun clearChat() {
        stopGeneration()
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { llama.unload() }
    }
}
