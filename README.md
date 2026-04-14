# Ollama 4 Android

Run large language models (Gemma 3, Qwen, SmolLM) locally on your Android device — completely offline, private, and fast. Powered by [llama.cpp](https://github.com/ggerganov/llama.cpp).

> **Version:** 1.0.3  
> **Package:** `io.github.kevinkicho.ollama4android`  
> **Repository:** [github.com/kevinkicho/Ollama4Android](https://github.com/kevinkicho/Ollama4Android)  
> **License:** [MIT](LICENSE)

## Attribution

This project was built entirely by **Claude** (Anthropic's AI assistant), specifically **Claude Opus 4** (`claude-opus-4-6`), through an interactive pair-programming session with [Kevin Kicho](https://github.com/kevinkicho) using [Claude Code](https://claude.ai/claude-code) (Anthropic's CLI for Claude). Every source file — Kotlin, C++, CMake, Gradle, XML resources, and this README — was authored by Claude in a single multi-hour session, with Kevin providing device testing, design direction, and iterative feedback on a Samsung Galaxy S22 Ultra and Galaxy Tab S7 connected via USB.

## Features

- **Local LLM inference** — Run GGUF models natively on Android using llama.cpp
- **KleidiAI acceleration** — ARM64 dotprod + fp16 NEON kernels for quantized inference
- **OpenMP multi-threading** — Parallel CPU inference across all performance cores
- **Ollama-compatible API server** — Local HTTP API (`/api/chat`, `/api/generate`, `/api/tags`, `/api/version`) so other apps on the device can use your loaded model
- **Streaming chat UI** — Real-time token generation with tokens/second display
- **Chat history** — Persistent chat sessions with SQLite, create/load/delete conversations
- **Model management** — Download, delete, and switch models directly from Hugging Face
- **Persistent background services** — Inference and API server survive app swipe-away (Termux-style) with wake locks
- **Configurable API port** — Set a fixed port or use `0` for OS auto-assignment
- **Model loading overlay** — Blocks interaction during model load to prevent crashes
- **Responsive tablet UI** — Chat bubbles adapt width to screen size (phone and tablet)
- **Long-press copy** — Copy any message to clipboard with a long press
- **Guided setup wizard** — Samsung-specific deep-links, battery optimization, notification permissions
- **Material 3 (Material You)** — Adaptive theming with dynamic colors
- **Shutdown controls** — Stop all background services from Settings or notification actions
- **No telemetry** — No internet required after model download, no cloud dependency

## Tested Devices

| Device | SoC | GPU | RAM | Android | Performance |
|--------|-----|-----|-----|---------|-------------|
| Samsung Galaxy S22 Ultra | Snapdragon 8 Gen 1 | Adreno 730 | 8/12 GB | Android 14 (One UI 6) | **11.1 tokens/sec** (Gemma 3 1B Q4) |
| Samsung Galaxy Tab S7 | Snapdragon 865+ | Adreno 650 | 6 GB | Android 13 (One UI 5) | Tested and working |

## Supported Models

| Model | File Size | RAM Needed | Best For |
|-------|-----------|------------|----------|
| Gemma 3 1B (Q4_K_M) | ~700 MB | ~1.5 GB | All devices, fast responses |
| Gemma 3 1B (Q8_0) | ~1.4 GB | ~2 GB | Better quality, still fast |
| Gemma 3 4B (Q4_K_M) | ~2.7 GB | ~4 GB | 8GB+ RAM devices, smarter |
| SmolLM2 135M (Q8_0) | ~150 MB | ~0.5 GB | Testing, ultra-fast |
| Qwen 2.5 0.5B (Q4_K_M) | ~400 MB | ~1 GB | Multilingual support |

## Local API Server

Ollama 4 Android includes a built-in Ollama-compatible HTTP API server. When enabled, other apps on your device (or your PC via `adb forward`) can send requests to the loaded model.

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Server status check |
| `/api/version` | GET | Returns server version |
| `/api/tags` | GET | Lists locally downloaded models |
| `/api/generate` | POST | Text completion (streaming/non-streaming) |
| `/api/chat` | POST | Chat completion with message history (streaming/non-streaming) |

### Setup

1. Go to **Settings** → **API Server** card
2. Select a model from the dropdown (or load one in Chat first)
3. Set a port (or leave as `0` for auto-assignment)
4. Toggle the server **ON**
5. The server runs as a foreground service with a notification — tap **Stop Server** in the notification to stop it

### Usage from PC

```bash
# Forward the port from your phone to your PC via ADB
adb forward tcp:11435 tcp:<actual-port>

# Test the API
curl http://localhost:11435/api/tags

# Chat completion
curl http://localhost:11435/api/chat -d '{
  "model": "local",
  "messages": [{"role": "user", "content": "Hello!"}],
  "stream": false
}'
```

### Streaming

By default, responses stream as newline-delimited JSON (NDJSON). Set `"stream": false` for a single JSON response.

Supported parameters: `num_predict`, `temperature`, `top_p`, `top_k`, `repeat_penalty`.

## Build Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **Android NDK** 27.2.12479018 (r27b) — install via SDK Manager
- **CMake** 3.31.6 — install via SDK Manager (`sdkmanager "cmake;3.31.6"`)
- **Git** — to clone llama.cpp

## Build Instructions

```bash
# 1. Clone this repository (with llama.cpp submodule)
git clone --recursive https://github.com/kevinkicho/Ollama4Android.git
cd Ollama4Android

# 2. Create keystore.properties for release signing (optional)
cat > keystore.properties << 'EOF'
storeFile=../release-keystore.jks
storePassword=your_password
keyAlias=ollama-android
keyPassword=your_password
EOF

# 3. Build debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 4. Build release APK (requires keystore.properties + .jks file)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# 5. Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** First build takes 3-5 minutes (compiling llama.cpp C++ code). Subsequent builds are incremental (~5 seconds).

## Usage

1. **Setup:** On first launch, the guided wizard helps configure battery optimization and notification permissions
2. **Download a model:** Go to the Models tab, tap Download on Gemma 3 1B (recommended starter)
3. **Load the model:** Go to Chat tab, tap the chip icon, select your downloaded model
4. **Chat:** Type a message and tap send — tokens stream in real-time
5. **API Server (optional):** Go to Settings, select a model in the API Server card, set a port, and toggle it on

## Project Structure

```
ollama4android/
├── app/
│   ├── build.gradle.kts                          # App build config: NDK, CMake, Compose, signing
│   ├── proguard-rules.pro                         # R8 keep rules for JNI, OkHttp, NanoHTTPD, coroutines
│   └── src/main/
│       ├── AndroidManifest.xml                    # Permissions, services, activity declarations
│       ├── cpp/
│       │   ├── CMakeLists.txt                     # Native build: llama.cpp, KleidiAI, OpenMP, NEON flags
│       │   ├── ollama_jni.cpp                     # JNI bridge: model load, context create, token streaming
│       │   └── host-toolchain.cmake               # MSVC toolchain for Vulkan shader gen (Windows host)
│       ├── java/com/ollama/android/
│       │   ├── MainActivity.kt                    # Single-activity entry point hosting Compose UI
│       │   ├── OllamaApp.kt                       # Application class: backend init, notification channels
│       │   ├── api/
│       │   │   └── OllamaApiServer.kt             # Ollama-compatible HTTP API (NanoHTTPD)
│       │   ├── data/
│       │   │   ├── ChatMessage.kt                 # Chat message data class with role, content, streaming state
│       │   │   ├── Model.kt                       # Model catalog: predefined GGUF models from Hugging Face
│       │   │   ├── ModelRepository.kt             # Downloads GGUF files with OkHttp progress tracking
│       │   │   └── db/
│       │   │       ├── ChatDatabase.kt            # Room database for persistent chat history
│       │   │       └── ChatDao.kt                 # DAO for chat sessions and messages
│       │   ├── llama/
│       │   │   └── LlamaAndroid.kt                # Kotlin JNI wrapper: callbackFlow streaming, coroutine bridge
│       │   ├── service/
│       │   │   ├── InferenceService.kt            # Foreground service: keeps inference alive, wake lock
│       │   │   └── ApiServerService.kt            # Foreground service: runs HTTP API server, wake lock
│       │   ├── ui/
│       │   │   ├── OllamaAndroidApp.kt            # Navigation scaffold: Chat, Models, Settings tabs
│       │   │   ├── chat/
│       │   │   │   ├── ChatScreen.kt              # Chat UI: bubbles, input bar, model selector, loading overlay
│       │   │   │   └── ChatViewModel.kt           # Chat state, prompt building, generation, session management
│       │   │   ├── models/
│       │   │   │   ├── ModelsScreen.kt            # Model download/delete UI with progress indicators
│       │   │   │   └── ModelsViewModel.kt         # Model list state, download orchestration
│       │   │   ├── settings/
│       │   │   │   └── SettingsScreen.kt          # API server config, port, model selector, shutdown controls
│       │   │   ├── setup/
│       │   │   │   └── SetupScreen.kt             # Guided wizard: battery, notifications, dev options
│       │   │   └── theme/
│       │   │       └── Theme.kt                   # Material 3 dynamic color theme configuration
│       │   └── util/
│       │       └── DeviceOptimization.kt          # Battery/dev-options detection, Samsung deep-links
│       └── res/
│           ├── drawable/ic_launcher_foreground.xml # Llama silhouette vector icon (adaptive foreground)
│           ├── mipmap-anydpi-v26/                 # Adaptive icon with monochrome for Material You
│           └── values/                            # Colors, strings, theme definitions
├── docs/
│   └── privacy-policy.html                        # Privacy policy for Google Play / F-Droid
├── fastlane/metadata/android/en-US/              # F-Droid / Play Store metadata
│   ├── full_description.txt                       # Full app description for store listing
│   ├── short_description.txt                      # 80-character summary
│   ├── title.txt                                  # App title
│   └── changelogs/1.txt                           # v1.0.0 changelog
├── build.gradle.kts                               # Root Gradle: AGP 8.7.3, Kotlin 1.9.24
├── settings.gradle.kts                            # Project settings and repository configuration
├── gradle.properties                              # JVM args, AndroidX, non-transitive R classes
└── .gitignore                                     # Excludes llama.cpp, build outputs, keystores
```

## How It Works

1. **llama.cpp** is compiled as a static C++ library for `arm64-v8a` via Android NDK with KleidiAI ARM kernels, OpenMP threading, and NEON SIMD (`armv8.2-a+dotprod+fp16`)
2. **JNI bridge** (`ollama_jni.cpp`) exposes model loading, context creation, and streaming token generation to Kotlin via native method calls
3. **LlamaAndroid.kt** wraps JNI calls with Kotlin `callbackFlow` for non-blocking streaming — tokens flow from C++ through JNI callbacks into Compose UI
4. **ModelRepository** downloads GGUF model files from Hugging Face using OkHttp with real-time progress tracking
5. **ChatViewModel** builds model-aware chat prompts (Gemma format or ChatML) and manages generation lifecycle with persistent session history
6. **OllamaApiServer** exposes an Ollama-compatible HTTP API via NanoHTTPD, auto-detecting the loaded model to choose the correct prompt format
7. **Foreground services** with partial wake locks keep inference and the API server running even when the app is swiped away or the screen is off
8. **Jetpack Compose** renders the Material 3 chat interface with streaming message updates, responsive layout, and model loading overlay

## CPU Optimization Stack

| Optimization | Effect | Requirement |
|-------------|--------|-------------|
| **KleidiAI** | ARM's hand-tuned assembly kernels for quantized matmul | CMake 3.28+, ARM64 |
| **OpenMP** | Multi-threaded inference across CPU cores | NDK r27+ |
| **NEON dotprod** | Hardware dot-product instructions for Q4/Q8 quantization | ARMv8.2-A+ |
| **NEON fp16** | Half-precision float operations | ARMv8.2-A+ |
| **-O3** | Aggressive compiler optimization | — |

Combined, these deliver **~15x speedup** over a naive build (0.7 t/s → 11.1 t/s on Gemma 3 1B Q4).

## Performance Tips

- Use **Gemma 3 1B Q4_K_M** for the best speed/quality balance on most devices
- Close other apps before loading a model to maximize available RAM
- Keep device plugged in for extended inference sessions
- The first response is slower (model warmup); subsequent prompts are faster
- Context length is set to 4096 tokens — balances conversation length and memory usage
- Disable battery optimization for best background inference performance

## Distribution

| Store | Status |
|-------|--------|
| [Google Play](https://play.google.com/store/apps/details?id=io.github.kevinkicho.ollama4android) | Closed testing |
| [F-Droid](https://f-droid.org/) | RFP submitted (#3794) |
| [GitHub Releases](https://github.com/kevinkicho/Ollama4Android/releases) | Available |

## Privacy

All inference runs entirely on-device. No data is collected, transmitted, or stored externally. The only network access is for downloading model files from Hugging Face. See the full [Privacy Policy](https://kevinkicho.github.io/Ollama4Android/privacy-policy.html).

## Versioning

This project follows [Semantic Versioning](https://semver.org/):

| Version | versionCode | Notes |
|---------|-------------|-------|
| **1.0.0** | 1 | Initial release: chat UI, model management, KleidiAI acceleration, setup wizard |
| **1.0.1** | 2 | Package ID change to `io.github.kevinkicho.ollama4android`, targetSdk 35 |
| **1.0.2** | 3 | Ollama-compatible API server, chat history, persistent services, tablet UI, loading overlay |
| **1.0.3** | 3 | Configurable port, model selector in API settings, shutdown controls, notification stop button |

## License

```
MIT License

Copyright (c) 2026 Kevin Kicho

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
