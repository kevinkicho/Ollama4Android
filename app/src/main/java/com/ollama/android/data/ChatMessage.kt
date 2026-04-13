package com.ollama.android.data

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
