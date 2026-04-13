# LiteRT Gateway

[English](README.md) | 中文版

Android 应用，在设备上 Host OpenAI 兼容 API，让手机成为本地 LLM API 服务器。

## 功能特性

- **OpenAI 兼容 API**：提供 `/v1/chat/completions`、`/v1/models`、`/health` 端点
- **多模态支持**：支持文本、图像、音频输入
- **流式输出**：支持 SSE 流式响应
- **可配置后端**：文本、图像、音频分别可选择 CPU/GPU/NPU 加速
- **Foreground Service**：后台持续运行
- **可配置端口**：默认 8080，可在设置中修改

## 技术栈

- **推理引擎**：LiteRT-LM (Google AI Edge)
- **HTTP 服务器**：Ktor Netty
- **平台**：Android (API 26+)

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/chat/completions` | POST | 聊天补全（支持流式） |
| `/v1/models` | GET | 获取可用模型 |
| `/health` | GET | 健康检查 |

## 请求示例

### 文本聊天

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}]}'
```

### 流式输出

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"数到5"}],"stream":true}'
```

### 图像识别

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "描述这张图片"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
      ]
    }]
  }'
```

### 音频识别

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "描述这段音频"},
        {"type": "audio", "audio": {"url": "data:audio/mp3;base64,..."}}
      ]
    }]
  }'
```

## 安装

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/Wolfpkhan/lite_rt_gateway.git
cd lite_rt_gateway

# 构建 debug APK
./gradlew assembleDebug

# 安装
adb install app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions 自动构建

推送 tag 自动触发构建和 Release：

```bash
git tag v1.0.0
git push --tags
```

构建产物会在 Actions 和 Release 页面提供下载。

## 使用方法

1. **安装模型**：将 `.litertlm` 模型文件放入应用私有目录：
   ```bash
   adb push your_model.litertlm /storage/emulated/0/Android/data/com.litert.gateway/files/models/
   ```

2. **启动应用**：点击 "启动" 按钮启动服务

3. **配置**：点击 "设置" 按钮可配置：
   - 端口号
   - 调试日志
   - 模型目录
   - 文本/图像/音频后端 (CPU/GPU/NPU)

4. **测试 API**：
   ```bash
   # 端口转发（如通过 USB 调试）
   adb forward tcp:12345 tcp:8080

   # 测试
   curl http://localhost:12345/health
   ```

## 权限说明

- `INTERNET`：网络访问
- `FOREGROUND_SERVICE`：后台服务
- `MANAGE_EXTERNAL_STORAGE`：访问外部存储（可选，用于选择外部目录的模型）
- `POST_NOTIFICATIONS`：通知权限（Android 13+）

## 项目结构

```
app/src/main/java/com/litert/gateway/
├── MainActivity.kt      # 主界面
├── SettingsActivity.kt  # 设置界面
├── LlmService.kt       # Foreground Service
├── LlmEngine.kt         # LiteRT-LM 封装
└── openai/
    ├── OpenAiModels.kt # 数据模型
    └── OpenAiRoute.kt   # Ktor 路由
```

## License

MIT
