package com.ollama.android.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Ollama cloud model inference.
 * Calls https://ollama.com/api/chat with Bearer token auth and streaming NDJSON.
 */
class OllamaCloudClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "OllamaCloudClient"
        const val BASE_URL = "https://ollama.com"

        @Volatile
        private var instance: OllamaCloudClient? = null

        fun getInstance(): OllamaCloudClient {
            return instance ?: synchronized(this) {
                instance ?: OllamaCloudClient().also { instance = it }
            }
        }
    }

    data class ChatMessage(
        val role: String,
        val content: String
    )

    private fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float = 0.7f
    ): String {
        val messagesArray = JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", stream)
            if (temperature != 0.7f) {
                put("options", JSONObject().apply {
                    put("temperature", temperature.toDouble())
                })
            }
        }.toString()
    }

    /**
     * Stream a chat completion from the Ollama cloud API.
     * Returns a Flow of token strings as they arrive.
     */
    fun chatStream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f
    ): Flow<String> = channelFlow {
        val body = buildRequestBody(model, messages, stream = true, temperature = temperature)

        Log.d(TAG, "Starting cloud chat stream: model=$model, messages=${messages.size}")

        val request = Request.Builder()
            .url("$BASE_URL/api/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        try {
            val response = call.execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Cloud API error ${response.code}: $errorBody")
                throw Exception("Cloud API error ${response.code}: $errorBody")
            }

            val inputStream = response.body?.byteStream()
                ?: throw Exception("Empty response body")

            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) continue
                try {
                    val json = JSONObject(line!!)
                    val message = json.optJSONObject("message")
                    val content = message?.optString("content", "") ?: ""
                    if (content.isNotEmpty()) {
                        send(content)
                    }
                    if (json.optBoolean("done", false)) {
                        Log.d(TAG, "Cloud stream done")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed line: ${line?.take(100)}", e)
                }
            }

            reader.close()
            response.close()
        } catch (e: Exception) {
            if (!isClosedForSend) {
                Log.e(TAG, "Cloud stream error", e)
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming chat completion. Returns the full response content.
     */
    fun chatBlocking(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f
    ): String {
        val body = buildRequestBody(model, messages, stream = false, temperature = temperature)

        Log.d(TAG, "Starting cloud chat blocking: model=$model, messages=${messages.size}")

        val request = Request.Builder()
            .url("$BASE_URL/api/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "Cloud API error ${response.code}: $errorBody")
            throw Exception("Cloud API error ${response.code}: $errorBody")
        }

        val responseBody = response.body?.string()
            ?: throw Exception("Empty response body")

        val json = JSONObject(responseBody)
        val message = json.optJSONObject("message")
        return message?.optString("content", "") ?: ""
    }

    /**
     * Stream cloud chat and write NDJSON chunks to a PipedOutputStream (for API server proxy).
     * Writes Ollama-format streaming responses.
     */
    fun chatStreamToPipe(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        pipedOut: PipedOutputStream,
        temperature: Float = 0.7f
    ) {
        val body = buildRequestBody(model, messages, stream = true, temperature = temperature)

        Log.d(TAG, "Starting cloud chat stream-to-pipe: model=$model")

        val request = Request.Builder()
            .url("$BASE_URL/api/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Cloud proxy error ${response.code}: $errorBody")
                val errorChunk = JSONObject().apply {
                    put("error", "Cloud API error ${response.code}: $errorBody")
                }.toString() + "\n"
                pipedOut.write(errorChunk.toByteArray(Charsets.UTF_8))
                pipedOut.flush()
                return
            }

            val inputStream = response.body?.byteStream() ?: return
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?

            // Pass through the NDJSON lines directly — they're already in Ollama format
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) continue
                pipedOut.write((line!! + "\n").toByteArray(Charsets.UTF_8))
                pipedOut.flush()

                // Check if done
                try {
                    val json = JSONObject(line!!)
                    if (json.optBoolean("done", false)) break
                } catch (_: Exception) {}
            }

            reader.close()
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Cloud stream-to-pipe error", e)
            try {
                val errorChunk = JSONObject().apply {
                    put("error", e.message ?: "Cloud proxy error")
                }.toString() + "\n"
                pipedOut.write(errorChunk.toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (_: Exception) {}
        }
    }
}
