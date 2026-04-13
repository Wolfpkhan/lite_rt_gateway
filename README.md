# LiteRT Gateway

[中文版](README_zh.md) | English

Android app that hosts an OpenAI-compatible API on your device, turning your phone into a local LLM API server.

## Features

- **OpenAI Compatible API**: Endpoints for `/v1/chat/completions`, `/v1/models`, `/health`
- **Google Gemma 4 Support**: Full support for Google Gemma 4 series models including text and multimodal variants
- **Multi-modal Support**: Text, image, and audio input
- **Streaming Output**: SSE streaming responses
- **Configurable Backends**: CPU/GPU/NPU acceleration for text, image, and audio
- **Foreground Service**: Runs continuously in background
- **Configurable Port**: Default 8080, changeable in settings

## Tech Stack

- **Inference Engine**: LiteRT-LM (Google AI Edge)
- **HTTP Server**: Ktor Netty
- **Platform**: Android (API 26+)

## Supported Models

This app supports models converted to the `.litertlm` format using Google's AI Edge tools. Currently tested with:

- **Google Gemma 4 Series**
  - Gemma 4 (text-only variants)
  - Gemma 4 with vision capabilities
  - Gemma 4 multimodal variants (text, image, audio)

To convert models to `.litertlm` format, use the [AI Edge Torch](https://ai.google.dev/edge/ai-edge-torch) conversion tools.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/v1/chat/completions` | POST | Chat completion (streaming supported) |
| `/v1/models` | GET | List available models |
| `/health` | GET | Health check |

## Request Examples

### Text Chat

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello"}]}'
```

### Streaming

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Count to 5"}],"stream":true}'
```

### Image Recognition

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Describe this image"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
      ]
    }]
  }'
```

### Audio Recognition

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "Describe this audio"},
        {"type": "audio", "audio": {"url": "data:audio/mp3;base64,..."}}
      ]
    }]
  }'
```

## Installation

### Build from Source

```bash
# Clone repo
git clone https://github.com/Wolfpkhan/lite_rt_gateway.git
cd lite_rt_gateway

# Build debug APK
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions Auto Build

Push a tag to trigger build and release:

```bash
git tag v1.0.0
git push --tags
```

Build artifacts available on Actions and Release pages.

## Usage

1. **Install Model**: Place `.litertlm` model file in app's private directory:
   ```bash
   # Example for Gemma 4 models
   adb push gemma-4-2b-it.litertlm /storage/emulated/0/Android/data/com.litert.gateway/files/models/
   adb push gemma-4-vision-2b.litertlm /storage/emulated/0/Android/data/com.litert.gateway/files/models/
   ```

2. **Start App**: Tap "Start" button to start the service

3. **Configure**: Tap "Settings" to configure:
   - Port number
   - Debug log
   - Model directory
   - Text/Image/Audio backend (CPU/GPU/NPU)

4. **Test API**:
   ```bash
   # Port forward (USB debugging)
   adb forward tcp:12345 tcp:8080

   # Test
   curl http://localhost:12345/health
   ```

## Permissions

- `INTERNET`: Network access
- `FOREGROUND_SERVICE`: Background service
- `MANAGE_EXTERNAL_STORAGE`: External storage access (optional, for selecting external model directory)
- `POST_NOTIFICATIONS`: Notification permission (Android 13+)

## Project Structure

```
app/src/main/java/com/litert/gateway/
├── MainActivity.kt      # Main UI
├── SettingsActivity.kt  # Settings UI
├── LlmService.kt       # Foreground Service
├── LlmEngine.kt         # LiteRT-LM wrapper
└── openai/
    ├── OpenAiModels.kt # Data models
    └── OpenAiRoute.kt   # Ktor routes
```

## License

MIT
