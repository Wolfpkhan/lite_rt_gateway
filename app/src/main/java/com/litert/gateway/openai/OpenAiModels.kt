package com.litert.gateway.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray

@Serializable
data class OpenAIRequest(
    val model: String = "litert-lm",
    val messages: List<Message>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

@Serializable
data class Message(
    val role: String,
    val content: String? = null
)

@Serializable
data class ImageUrlContent(
    val url: String
)

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: ImageUrlContent? = null
)

@Serializable
data class OpenAIResponse(
    val id: String,
    val model: String = "litert-lm",
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val message: Message,
    @SerialName("finish_reason")
    val finishReason: String = "stop"
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)

@Serializable
data class StreamChunk(
    val id: String,
    val choices: List<StreamChoice>
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val role: String,
    val content: String = ""
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object")
    val objectType: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    @SerialName("owned_by")
    val ownedBy: String = "LiteRT"
)

@Serializable
data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelInfo> = emptyList()
)
