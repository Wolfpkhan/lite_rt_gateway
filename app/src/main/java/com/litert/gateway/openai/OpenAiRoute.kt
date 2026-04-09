package com.litert.gateway.openai

import com.litert.gateway.LlmEngine
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.chatCompletionsRoute(llmEngine: LlmEngine, onDebug: ((String) -> Unit)? = null) {
    post("/v1/chat/completions") {
        val jsonBody = call.receiveText()

        if (!llmEngine.isReady()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                "{\"error\":{\"message\":\"LLM engine not ready\",\"type\":\"service_unavailable\"}}"
            )
            return@post
        }

        try {
            val json = Json.parseToJsonElement(jsonBody)
            val messagesJson = json.jsonObject["messages"]?.jsonArray

            if (messagesJson == null) {
                call.respond(HttpStatusCode.BadRequest, "No messages provided")
                return@post
            }

            // Deserialize the complete messages array
            val messages = messagesJson.map { msgJson ->
                val msgObj = msgJson.jsonObject
                Message(
                    role = msgObj["role"]?.jsonPrimitive?.content ?: "user",
                    content = msgObj["content"]?.jsonPrimitive?.content
                )
            }

            if (messages.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Messages array is empty")
                return@post
            }

            val stream = json.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
            val temperature = json.jsonObject["temperature"]?.jsonPrimitive?.doubleOrNull

            onDebug?.invoke(">>> Request with ${messages.size} messages")

            if (stream) {
                call.respondOutputStream(
                    ContentType.Text.EventStream,
                    HttpStatusCode.OK
                ) {
                    val outputStream = this
                    val id = "litert-${System.currentTimeMillis()}"

                    llmEngine.chatStreamWithMessages(messages, temperature).collect { chunk ->
                        onDebug?.invoke("<<< Stream: $chunk")
                        val jsonStr = Json.encodeToString(
                            StreamChunk.serializer(),
                            StreamChunk(
                                id = id,
                                choices = listOf(
                                    StreamChoice(delta = StreamDelta("assistant", chunk))
                                )
                            )
                        )
                        outputStream.write("data: $jsonStr\n\n".toByteArray())
                        outputStream.flush()
                    }

                    val finalJson = Json.encodeToString(
                        StreamChunk.serializer(),
                        StreamChunk(
                            id = id,
                            choices = listOf(
                                StreamChoice(
                                    delta = StreamDelta("assistant", ""),
                                    finishReason = "stop"
                                )
                            )
                        )
                    )
                    outputStream.write("data: $finalJson\n\n".toByteArray())
                    outputStream.write("data: [DONE]\n\n".toByteArray())
                    outputStream.flush()
                }
            } else {
                // Use the new chatWithMessages method with full history
                val result = llmEngine.chatWithMessages(messages, temperature)
                onDebug?.invoke("<<< Model Output: $result")

                val choicesArray = buildJsonArray {
                    add(buildJsonObject {
                        put("finish_reason", "stop")
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", result)
                        })
                    })
                }
                val usageObj = buildJsonObject {
                    // Estimate token count based on all messages
                    val totalInputChars = messages.sumOf { it.content?.length ?: 0 }
                    put("prompt_tokens", totalInputChars / 4)
                    put("completion_tokens", result.length / 4)
                    put("total_tokens", (totalInputChars + result.length) / 4)
                }
                val response = buildJsonObject {
                    put("id", "litert-${System.currentTimeMillis()}")
                    put("model", "litert-lm")
                    put("choices", choicesArray)
                    put("usage", usageObj)
                }

                call.respondText(response.toString(), ContentType.Application.Json)
            }
        } catch (e: Exception) {
            onDebug?.invoke("!!! Error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
        }
    }
}

private fun parseMessageContent(message: JsonObject): Pair<String, List<String>> {
    val textBuilder = StringBuilder()
    val mediaUrls = mutableListOf<String>()

    val content = message["content"]

    when (content) {
        is JsonPrimitive -> {
            // Plain text content
            textBuilder.append(content.content)
        }
        is JsonArray -> {
            // Multi-modal content array
            for (item in content) {
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "text" -> textBuilder.append(obj["text"]?.jsonPrimitive?.content ?: "")
                    "image_url" -> {
                        val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                        if (url != null) mediaUrls.add(url)
                    }
                    "audio" -> {
                        val url = obj["audio"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                        if (url != null) mediaUrls.add(url)
                    }
                }
            }
        }
        else -> {
            // Fallback for other types
        }
    }

    return Pair(textBuilder.toString(), mediaUrls)
}

fun Route.modelsRoute() {
    get("/v1/models") {
        call.respond(
            ModelListResponse(
                data = listOf(ModelInfo(id = "litert-lm"))
            )
        )
    }
}

fun Route.healthRoute(llmEngine: LlmEngine) {
    get("/health") {
        call.respond(
            if (llmEngine.isReady()) {
                mapOf("status" to "ready", "engine" to "LiteRT-LM")
            } else {
                mapOf("status" to "not_ready", "engine" to "LiteRT-LM")
            }
        )
    }
}
