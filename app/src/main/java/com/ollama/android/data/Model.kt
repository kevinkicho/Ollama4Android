package com.ollama.android.data

/**
 * Represents an available LLM model that can be downloaded and run.
 */
data class AvailableModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val ramRequired: String,
    val quantization: String,
    val huggingFaceRepo: String,
    val fileName: String,
    val recommended: Boolean = false,
    val isCloud: Boolean = false,
    val cloudModelTag: String? = null
)

/**
 * Represents a model available for use (local or cloud).
 */
data class LocalModel(
    val id: String,
    val name: String,
    val filePath: String,
    val sizeBytes: Long,
    val quantization: String,
    val isCloud: Boolean = false,
    val cloudModelTag: String? = null
)

/**
 * Download state for a model.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    data object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Catalog of models known to work well on Android.
 * These are pre-quantized GGUF models from Hugging Face.
 */
object ModelCatalog {

    val models = listOf(
        AvailableModel(
            id = "gemma3-1b-q4",
            name = "Gemma 3 1B",
            description = "Google's lightweight model. Great for simple tasks, fast on all devices.",
            sizeBytes = 750_000_000L,
            ramRequired = "~1.5 GB",
            quantization = "Q4_K_M",
            huggingFaceRepo = "bartowski/google_gemma-3-1b-it-GGUF",
            fileName = "google_gemma-3-1b-it-Q4_K_M.gguf",
            recommended = true
        ),
        AvailableModel(
            id = "gemma3-1b-q8",
            name = "Gemma 3 1B (Q8)",
            description = "Higher quality quantization of Gemma 3 1B. Better responses, slightly larger.",
            sizeBytes = 1_400_000_000L,
            ramRequired = "~2 GB",
            quantization = "Q8_0",
            huggingFaceRepo = "bartowski/google_gemma-3-1b-it-GGUF",
            fileName = "google_gemma-3-1b-it-Q8_0.gguf",
            recommended = false
        ),
        AvailableModel(
            id = "gemma3-4b-q4",
            name = "Gemma 3 4B",
            description = "Larger Gemma model. Smarter responses but needs more RAM (8GB+ device).",
            sizeBytes = 2_700_000_000L,
            ramRequired = "~4 GB",
            quantization = "Q4_K_M",
            huggingFaceRepo = "bartowski/google_gemma-3-4b-it-GGUF",
            fileName = "google_gemma-3-4b-it-Q4_K_M.gguf",
            recommended = false
        ),
        AvailableModel(
            id = "smollm2-135m-q8",
            name = "SmolLM2 135M",
            description = "Tiny model for testing. Very fast but limited capability.",
            sizeBytes = 150_000_000L,
            ramRequired = "~0.5 GB",
            quantization = "Q8_0",
            huggingFaceRepo = "bartowski/SmolLM2-135M-Instruct-GGUF",
            fileName = "SmolLM2-135M-Instruct-Q8_0.gguf",
            recommended = false
        ),
        AvailableModel(
            id = "qwen2.5-0.5b-q4",
            name = "Qwen 2.5 0.5B",
            description = "Alibaba's tiny model. Good multilingual support.",
            sizeBytes = 400_000_000L,
            ramRequired = "~1 GB",
            quantization = "Q4_K_M",
            huggingFaceRepo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            recommended = false
        )
    )

    fun getById(id: String): AvailableModel? = models.find { it.id == id }

    val cloudModels = listOf(
        AvailableModel(
            id = "cloud-deepseek-v3.1-671b",
            name = "DeepSeek V3.1 671B",
            description = "DeepSeek's largest model. Exceptional at reasoning and coding tasks.",
            sizeBytes = 0L,
            ramRequired = "Cloud",
            quantization = "Cloud",
            huggingFaceRepo = "",
            fileName = "",
            isCloud = true,
            cloudModelTag = "deepseek-v3.1:671b-cloud"
        ),
        AvailableModel(
            id = "cloud-qwen3-coder-480b",
            name = "Qwen 3 Coder 480B",
            description = "Alibaba's massive coding model. Top-tier code generation.",
            sizeBytes = 0L,
            ramRequired = "Cloud",
            quantization = "Cloud",
            huggingFaceRepo = "",
            fileName = "",
            isCloud = true,
            cloudModelTag = "qwen3-coder:480b-cloud"
        ),
        AvailableModel(
            id = "cloud-gpt-oss-120b",
            name = "GPT-OSS 120B",
            description = "Open-source GPT model. Strong general-purpose performance.",
            sizeBytes = 0L,
            ramRequired = "Cloud",
            quantization = "Cloud",
            huggingFaceRepo = "",
            fileName = "",
            isCloud = true,
            cloudModelTag = "gpt-oss:120b-cloud"
        ),
        AvailableModel(
            id = "cloud-gpt-oss-20b",
            name = "GPT-OSS 20B",
            description = "Smaller open-source GPT model. Fast cloud inference.",
            sizeBytes = 0L,
            ramRequired = "Cloud",
            quantization = "Cloud",
            huggingFaceRepo = "",
            fileName = "",
            isCloud = true,
            cloudModelTag = "gpt-oss:20b-cloud"
        )
    )

    val allModels = models + cloudModels

    fun getCloudById(id: String): AvailableModel? = cloudModels.find { it.id == id }
}
