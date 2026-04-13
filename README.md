# Ollama 4 Android

Run large language models (Gemma 3, Qwen, SmolLM) locally on your Android device — completely offline, private, and fast. Powered by [llama.cpp](https://github.com/ggerganov/llama.cpp).

> **Version:** 1.0.0  
> **Repository:** [github.com/kevinkicho/Ollama4Android](https://github.com/kevinkicho/Ollama4Android)  
> **License:** [MIT](LICENSE)

## Attribution

This project was built entirely by **Claude** (Anthropic's AI assistant), specifically **Claude Opus 4** (`claude-opus-4-6`), through an interactive pair-programming session with [Kevin Kicho](https://github.com/kevinkicho) using [Claude Code](https://claude.ai/claude-code) (Anthropic's CLI for Claude). Every source file — Kotlin, C++, CMake, Gradle, XML resources, and this README — was authored by Claude in a single multi-hour session, with Kevin providing device testing, design direction, and iterative feedback on a Samsung Galaxy S22 Ultra connected via USB.

## Features

- Run GGUF models natively on Android using llama.cpp as the inference backend
- KleidiAI-accelerated quantized inference on ARM64 (dotprod + fp16 NEON kernels)
- OpenMP multi-threaded CPU parallelism across all performance cores
- Download and manage models directly from Hugging Face
- Streaming chat UI with real-time token generation and tokens/second display
- Guided device setup wizard with Samsung-specific deep-links and auto-detection
- Foreground service keeps inference alive when the app is backgrounded
- Material 3 (Material You) UI with adaptive theming
- No internet required after model download — no telemetry, no cloud dependency

## Tested Devices

| Device | SoC | GPU | RAM | Android | Performance |
|--------|-----|-----|-----|---------|-------------|
| Samsung Galaxy S22 Ultra | Snapdragon 8 Gen 1 | Adreno 730 | 8/12 GB | Android 14 (One UI 6) | **11.1 tokens/sec** (Gemma 3 1B Q4) |
| Samsung Galaxy Tab S7 | Snapdragon 865+ | Adreno 650 | 6 GB | Android 13 (One UI 5) | Pending testing |

## Supported Models

| Model | File Size | RAM Needed | Best For |
|-------|-----------|------------|----------|
| Gemma 3 1B (Q4_K_M) | ~700 MB | ~1.5 GB | All devices, fast responses |
| Gemma 3 1B (Q8_0) | ~1.4 GB | ~2 GB | Better quality, still fast |
| Gemma 3 4B (Q4_K_M) | ~2.7 GB | ~4 GB | 8GB+ RAM devices, smarter |
| SmolLM2 135M (Q8_0) | ~150 MB | ~0.5 GB | Testing, ultra-fast |
| Qwen 2.5 0.5B (Q4_K_M) | ~400 MB | ~1 GB | Multilingual support |

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

## Project Structure

```
ollama4android/
├── app/
│   ├── build.gradle.kts                          # App build config: NDK, CMake, Compose, signing
│   ├── proguard-rules.pro                         # R8 keep rules for JNI, OkHttp, coroutines
│   └── src/main/
│       ├── AndroidManifest.xml                    # Permissions, services, activity declarations
│       ├── cpp/
│       │   ├── CMakeLists.txt                     # Native build: llama.cpp, KleidiAI, OpenMP, NEON flags
│       │   ├── ollama_jni.cpp                     # JNI bridge: model load, context create, token streaming
│       │   └── host-toolchain.cmake               # MSVC toolchain for Vulkan shader gen (Windows host)
│       ├── java/com/ollama/android/
│       │   ├── MainActivity.kt                    # Single-activity entry point hosting Compose UI
│       │   ├── OllamaApp.kt                       # Application class: backend init, notification channels
│       │   ├── data/
│       │   │   ├── ChatMessage.kt                 # Chat message data class with role, content, streaming state
│       │   │   ├── Model.kt                       # Model catalog: predefined GGUF models from Hugging Face
│       │   │   └── ModelRepository.kt             # Downloads GGUF files with OkHttp progress tracking
│       │   ├── llama/
│       │   │   └── LlamaAndroid.kt                # Kotlin JNI wrapper: callbackFlow streaming, coroutine bridge
│       │   ├── service/
│       │   │   └── InferenceService.kt            # Foreground service keeping inference alive in background
│       │   ├── ui/
│       │   │   ├── OllamaAndroidApp.kt            # Navigation scaffold: Chat, Models, Settings tabs
│       │   │   ├── chat/
│       │   │   │   ├── ChatScreen.kt              # Chat UI: message bubbles, input bar, model selector
│       │   │   │   └── ChatViewModel.kt           # Chat state, prompt building (Gemma format), generation
│       │   │   ├── models/
│       │   │   │   ├── ModelsScreen.kt            # Model download/delete UI with progress indicators
│       │   │   │   └── ModelsViewModel.kt         # Model list state, download orchestration
│       │   │   ├── settings/
│       │   │   │   └── SettingsScreen.kt          # Device info display, re-run setup, app version
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
5. **ChatViewModel** builds Gemma-format chat prompts (`<start_of_turn>user/model`) and manages generation lifecycle
6. **Jetpack Compose** renders the Material 3 chat interface with streaming message updates

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
- Context length is set to 1024 tokens — sufficient for chat, saves memory

## Versioning

This project follows [Semantic Versioning](https://semver.org/):

| Version | Date | Notes |
|---------|------|-------|
| **1.0.0** | 2026-04-13 | Initial release: chat UI, model management, KleidiAI acceleration, setup wizard |

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
