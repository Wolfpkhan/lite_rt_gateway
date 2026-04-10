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

            // Deserialize the complete messages array (including tool_calls)
            val messages = messagesJson.map { msgJson ->
                val msgObj = msgJson.jsonObject
                val role = msgObj["role"]?.jsonPrimitive?.content ?: "user"
                val content = msgObj["content"]?.jsonPrimitive?.content

                // Parse tool_calls if present
                val toolCalls = msgObj["tool_calls"]?.jsonArray?.map { tcJson ->
                    val tcObj = tcJson.jsonObject
                    ToolCall(
                        id = tcObj["id"]?.jsonPrimitive?.content ?: "call_${System.currentTimeMillis()}",
                        type = tcObj["type"]?.jsonPrimitive?.content ?: "function",
                        function = FunctionCall(
                            name = tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                            arguments = tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                        )
                    )
                }

                Message(
                    role = role,
                    content = content,
                    toolCalls = toolCalls,
                    toolCallId = msgObj["tool_call_id"]?.jsonPrimitive?.content
                )
            }

            if (messages.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Messages array is empty")
                return@post
            }

            // Parse tools from request
            val toolsJson = json.jsonObject["tools"]?.jsonArray
            val tools = toolsJson?.mapNotNull { toolJson ->
                val toolObj = toolJson.jsonObject
                val functionObj = toolObj["function"]?.jsonObject
                if (functionObj != null) {
                    Tool(
                        type = "function",
                        function = FunctionDefinition(
                            name = functionObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            description = functionObj["description"]?.jsonPrimitive?.content ?: "",
                            parameters = functionObj["parameters"]
                        )
                    )
                } else null
            }

            val stream = json.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
            val temperature = json.jsonObject["temperature"]?.jsonPrimitive?.doubleOrNull

            onDebug?.invoke(">>> Request with ${messages.size} messages, tools: ${tools?.size ?: 0}")

            // Calculate input size for logging
            val totalInputChars = messages.sumOf { msg ->
                msg.content?.length ?: 0
            }
            val inputTokens = if (totalInputChars > 0) totalInputChars / 4 else 1
            onDebug?.invoke(">>> Input size: ~$inputTokens tokens")

            if (stream) {
                call.respondOutputStream(
                    ContentType.Text.EventStream,
                    HttpStatusCode.OK
                ) {
                    val outputStream = this
                    val id = "litert-${System.currentTimeMillis()}"

                    llmEngine.chatStreamWithMessages(messages, temperature, tools).collect { chunk ->
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
                val result = llmEngine.chatWithMessages(messages, temperature, tools)

                // Calculate output size for logging
                val outputTokens = result.text.length / 4
                onDebug?.invoke(">>> Input: ~$inputTokens tokens, Output: ~$outputTokens tokens")
                onDebug?.invoke("<<< Model Output: ${result.text}, toolCalls: ${result.toolCalls?.size}")

                // Build the message with content and/or tool_calls
                val messageObj = buildJsonObject {
                    put("role", "assistant")
                    if (result.toolCalls?.isNotEmpty() == true) {
                        // Response with tool calls
                        val toolCallsArray = buildJsonArray {
                            result.toolCalls.forEachIndexed { index, tc ->
                                add(buildJsonObject {
                                    put("id", "call_${System.currentTimeMillis()}_$index")
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        // tc.arguments is a Map, convert to JSON string
                                        val argsJson = buildJsonObject {
                                            tc.arguments.forEach { (k, v) ->
                                                put(k, Json.encodeToJsonElement(v.toString()))
                                            }
                                        }.toString()
                                        put("arguments", argsJson)
                                    })
                                })
                            }
                        }
                        put("tool_calls", toolCallsArray)
                        put("content", result.text)
                    } else {
                        // Regular text response
                        put("content", result.text)
                    }
                }

                val finishReason = if (result.toolCalls?.isNotEmpty() == true) "tool_calls" else "stop"
                val choicesArray = buildJsonArray {
                    add(buildJsonObject {
                        put("finish_reason", finishReason)
                        put("message", messageObj)
                    })
                }
                val usageObj = buildJsonObject {
                    put("prompt_tokens", inputTokens)
                    put("completion_tokens", outputTokens)
                    put("total_tokens", inputTokens + outputTokens)
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
