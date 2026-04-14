package com.ollama.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages model downloads and local model storage.
 */
class ModelRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val _downloadStates = mutableMapOf<String, MutableStateFlow<DownloadState>>()

    fun getDownloadState(modelId: String): Flow<DownloadState> {
        return _downloadStates.getOrPut(modelId) {
            MutableStateFlow(
                if (isModelDownloaded(modelId)) DownloadState.Completed
                else DownloadState.Idle
            )
        }.asStateFlow()
    }

    private val prefs = context.getSharedPreferences("ollama_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString("cloud_api_key", "") ?: ""

    fun setApiKey(key: String) {
        prefs.edit().putString("cloud_api_key", key).apply()
    }

    /**
     * Get list of locally available models.
     */
    suspend fun getLocalModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        ModelCatalog.models.mapNotNull { available ->
            val file = File(modelsDir, available.fileName)
            if (file.exists()) {
                LocalModel(
                    id = available.id,
                    name = available.name,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    quantization = available.quantization
                )
            } else null
        }
    }

    /**
     * Get cloud models (always available when API key is set).
     */
    fun getCloudModels(): List<LocalModel> {
        if (getApiKey().isBlank()) return emptyList()
        return ModelCatalog.cloudModels.map { cloud ->
            LocalModel(
                id = cloud.id,
                name = cloud.name,
                filePath = "",
                sizeBytes = 0L,
                quantization = "Cloud",
                isCloud = true,
                cloudModelTag = cloud.cloudModelTag
            )
        }
    }

    /**
     * Get all available models (local + cloud).
     */
    suspend fun getAllAvailableModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        getLocalModels() + getCloudModels()
    }

    /**
     * Check if a model is already downloaded.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val model = ModelCatalog.getById(modelId) ?: return false
        return File(modelsDir, model.fileName).exists()
    }

    /**
     * Get the file path for a downloaded model.
     */
    fun getModelPath(modelId: String): String? {
        val model = ModelCatalog.getById(modelId) ?: return null
        val file = File(modelsDir, model.fileName)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Download a model from Hugging Face.
     */
    suspend fun downloadModel(modelId: String): Result<String> = withContext(Dispatchers.IO) {
        val model = ModelCatalog.getById(modelId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown model: $modelId"))

        val stateFlow = _downloadStates.getOrPut(modelId) { MutableStateFlow(DownloadState.Idle) }

        try {
            val url = "https://huggingface.co/${model.huggingFaceRepo}/resolve/main/${model.fileName}"
            val outputFile = File(modelsDir, model.fileName)
            val tempFile = File(modelsDir, "${model.fileName}.tmp")

            stateFlow.value = DownloadState.Downloading(0f, 0, model.sizeBytes)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OllamaAndroid/1.0")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                stateFlow.value = DownloadState.Error("Download failed: HTTP ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: run {
                stateFlow.value = DownloadState.Error("Empty response")
                return@withContext Result.failure(Exception("Empty response body"))
            }

            val totalBytes = body.contentLength().let {
                if (it > 0) it else model.sizeBytes
            }

            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        val progress = bytesDownloaded.toFloat() / totalBytes.toFloat()
                        stateFlow.value = DownloadState.Downloading(
                            progress.coerceIn(0f, 1f),
                            bytesDownloaded,
                            totalBytes
                        )
                    }
                }
            }

            // Rename temp file to final
            tempFile.renameTo(outputFile)
            stateFlow.value = DownloadState.Completed
            Result.success(outputFile.absolutePath)

        } catch (e: Exception) {
            stateFlow.value = DownloadState.Error(e.message ?: "Unknown error")
            // Clean up temp file
            File(modelsDir, "${model.fileName}.tmp").delete()
            Result.failure(e)
        }
    }

    /**
     * Delete a downloaded model.
     */
    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = ModelCatalog.getById(modelId) ?: return@withContext
        File(modelsDir, model.fileName).delete()
        _downloadStates[modelId]?.value = DownloadState.Idle
    }

    companion object {
        @Volatile
        private var instance: ModelRepository? = null

        fun getInstance(context: Context): ModelRepository {
            return instance ?: synchronized(this) {
                instance ?: ModelRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
