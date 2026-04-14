# llama.cpp JNI bridge — must not be renamed or stripped
-keep class com.ollama.android.llama.LlamaAndroid { *; }
-keep class com.ollama.android.llama.LlamaAndroid$* { *; }
-keepclassmembers class com.ollama.android.llama.LlamaAndroid {
    native <methods>;
}

# Data classes used across JNI and serialization boundaries
-keep class com.ollama.android.data.** { *; }

# OkHttp — needed for model downloads
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Compose runtime metadata
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# NanoHTTPD — embedded HTTP server for local API
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
