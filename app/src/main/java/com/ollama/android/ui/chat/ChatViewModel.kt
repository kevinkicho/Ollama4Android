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
import com.ollama.android.data.db.ChatDatabase
import com.ollama.android.data.db.ChatMessageEntity
import com.ollama.android.data.db.ChatSessionEntity
import com.ollama.android.llama.LlamaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatSession(
    val id: String,
    val title: String,
    val modelName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isModelLoading: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadedModelName: String? = null,
    val availableModels: List<LocalModel> = emptyList(),
    val error: String? = null,
    val tokensPerSecond: Float = 0f,
    val memoryUsageMb: Int = 0,
    val memoryLimitMb: Int = 0,
    val currentSessionId: String? = null,
    val sessions: List<ChatSession> = emptyList(),
    val contextUsedPercent: Int = 0,
    val contextTrimmed: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llama = LlamaAndroid.instance
    private val modelRepo = ModelRepository.getInstance(application)
    private val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val chatDao = ChatDatabase.getInstance(application).chatDao()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    companion object {
        @Volatile
        var currentLoadedModelName: String? = null
    }

    private var generationJob: Job? = null
    private val contextSize = 4096

    init {
        refreshLocalModels()
        updateMemoryUsage()
        loadSessions()
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

    private fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionEntities = chatDao.getAllSessions()
            val sessions = sessionEntities.map { entity ->
                val count = chatDao.getMessageCount(entity.id)
                ChatSession(
                    id = entity.id,
                    title = entity.title,
                    modelName = entity.modelName,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    messageCount = count
                )
            }
            _uiState.update { it.copy(sessions = sessions) }
        }
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
                _uiState.update { it.copy(error = null, isModelLoading = true) }
                llama.loadModel(model.filePath, nGpuLayers = 0)
                llama.createContext(nCtx = contextSize, nThreads = 4)
                currentLoadedModelName = model.name
                _uiState.update {
                    it.copy(
                        isModelLoaded = true,
                        isModelLoading = false,
                        loadedModelName = model.name
                    )
                }
                updateMemoryUsage()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Failed to load model: ${e.message}",
                        isModelLoaded = false,
                        isModelLoading = false,
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
            currentLoadedModelName = null
            _uiState.update {
                it.copy(isModelLoaded = false, loadedModelName = null)
            }
            updateMemoryUsage()
        }
    }

    fun startNewChat() {
        stopGeneration()
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            chatDao.insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    title = "New Chat",
                    modelName = _uiState.value.loadedModelName,
                    createdAt = now,
                    updatedAt = now
                )
            )
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = emptyList()
                )
            }
            loadSessions()
        }
    }

    fun loadSession(session: ChatSession) {
        stopGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            val messageEntities = chatDao.getMessagesForSession(session.id)
            val messages = messageEntities.map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = ChatMessage.Role.valueOf(entity.role),
                    content = entity.content,
                    timestamp = entity.timestamp,
                    isStreaming = false
                )
            }
            _uiState.update {
                it.copy(
                    currentSessionId = session.id,
                    messages = messages
                )
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.deleteSession(session.id)
            if (_uiState.value.currentSessionId == session.id) {
                _uiState.update {
                    it.copy(currentSessionId = null, messages = emptyList())
                }
            }
            loadSessions()
        }
    }

    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank() || _uiState.value.isGenerating) return

        val userMsg = ChatMessage(role = ChatMessage.Role.USER, content = userMessage)

        // Auto-create a session if none exists
        if (_uiState.value.currentSessionId == null) {
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            viewModelScope.launch(Dispatchers.IO) {
                chatDao.insertSession(
                    ChatSessionEntity(
                        id = sessionId,
                        title = userMessage.take(50),
                        modelName = _uiState.value.loadedModelName,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                _uiState.update { it.copy(currentSessionId = sessionId) }
                saveAndSend(userMsg)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                saveAndSend(userMsg)
            }
        }
    }

    private suspend fun saveAndSend(userMsg: ChatMessage) {
        val sessionId = _uiState.value.currentSessionId ?: return

        // Save user message to DB
        chatDao.insertMessage(
            ChatMessageEntity(
                id = userMsg.id,
                sessionId = sessionId,
                role = userMsg.role.name,
                content = userMsg.content,
                timestamp = userMsg.timestamp
            )
        )
        chatDao.updateSessionTimestamp(sessionId, System.currentTimeMillis())

        // Update title from first user message if it's still "New Chat"
        val sessions = chatDao.getAllSessions()
        val currentSession = sessions.find { it.id == sessionId }
        if (currentSession?.title == "New Chat") {
            chatDao.updateSessionTitle(sessionId, userMsg.content.take(50), System.currentTimeMillis())
        }

        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                error = null
            )
        }

        loadSessions()
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

                    if (tokenCount % 50 == 0) updateMemoryUsage()
                }

                // Mark as complete and save to DB
                val finalContent = responseBuilder.toString()
                val finalMsg = assistantMsg.copy(content = finalContent, isStreaming = false)

                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    messages[messages.lastIndex] = finalMsg
                    state.copy(messages = messages, isGenerating = false)
                }

                // Persist assistant message
                val sessionId = _uiState.value.currentSessionId
                if (sessionId != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                id = finalMsg.id,
                                sessionId = sessionId,
                                role = finalMsg.role.name,
                                content = finalContent,
                                timestamp = finalMsg.timestamp
                            )
                        )
                        chatDao.updateSessionTimestamp(sessionId, System.currentTimeMillis())
                        loadSessions()
                    }
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
        val allMessages = _uiState.value.messages
        val messages = trimMessagesToFit(allMessages)
        val wasTrimmed = messages.size < allMessages.size

        val prompt = when {
            modelName.contains("gemma") -> buildGemmaPrompt(messages)
            modelName.contains("qwen") -> buildChatMLPrompt(messages)
            modelName.contains("smollm") -> buildChatMLPrompt(messages)
            else -> buildChatMLPrompt(messages)
        }

        // Estimate context usage (prompt chars / 3 ≈ tokens)
        val estimatedTokens = prompt.length / 3
        val usedPercent = ((estimatedTokens.toFloat() / contextSize) * 100).toInt().coerceIn(0, 100)

        _uiState.update { it.copy(contextUsedPercent = usedPercent, contextTrimmed = wasTrimmed) }

        return prompt
    }

    private fun trimMessagesToFit(messages: List<ChatMessage>): List<ChatMessage> {
        // Conservative: ~3 chars per token, reserve 1024 tokens for generation
        val maxPromptChars = (contextSize - 1024) * 3

        var totalChars = messages.sumOf { it.content.length + 50 }
        if (totalChars <= maxPromptChars) return messages

        val trimmed = messages.toMutableList()
        while (trimmed.size > 2 && totalChars > maxPromptChars) {
            val removed = trimmed.removeAt(0)
            totalChars -= (removed.content.length + 50)
        }
        return trimmed
    }

    private fun buildGemmaPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                ChatMessage.Role.USER -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                ChatMessage.Role.ASSISTANT -> {
                    if (msg.content.isNotEmpty()) sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
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
                ChatMessage.Role.SYSTEM -> sb.append("<|im_start|>system\n${msg.content}<|im_end|>\n")
                ChatMessage.Role.USER -> sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                ChatMessage.Role.ASSISTANT -> {
                    if (msg.content.isNotEmpty()) sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
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

                // Save partial response
                val sessionId = state.currentSessionId
                val partialMsg = messages.last()
                if (sessionId != null && partialMsg.content.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                id = partialMsg.id,
                                sessionId = sessionId,
                                role = partialMsg.role.name,
                                content = partialMsg.content,
                                timestamp = partialMsg.timestamp
                            )
                        )
                    }
                }
            }
            state.copy(isGenerating = false, messages = messages)
        }
    }

    fun clearChat() {
        stopGeneration()
        _uiState.update { it.copy(messages = emptyList(), currentSessionId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { llama.unload() }
    }
}
