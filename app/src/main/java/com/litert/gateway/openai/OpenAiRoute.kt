package com.litert.gateway.openai

import com.litert.gateway.LlmEngine
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

fun Route.chatCompletionsRoute(llmEngine: LlmEngine, onDebug: ((String) -> Unit)? = null) {
    post("/v1/chat/completions") {
        val request = try {
            call.receive<OpenAIRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            return@post
        }

        // Debug log API request
        onDebug?.invoke(">>> API Request: ${Json.encodeToString(OpenAIRequest.serializer(), request)}")

        if (!llmEngine.isReady()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                "{\"error\":{\"message\":\"LLM engine not ready\",\"type\":\"service_unavailable\"}}"
            )
            return@post
        }

        val userMessage = request.messages.lastOrNull()?.content ?: ""
        onDebug?.invoke(">>> Model Input: $userMessage")

        if (request.stream) {
            call.respondOutputStream(
                ContentType.Text.EventStream,
                HttpStatusCode.OK
            ) {
                val outputStream = this
                val id = "litert-${System.currentTimeMillis()}"

                llmEngine.chatStream(userMessage, request.temperature).collect { chunk ->
                    onDebug?.invoke("<<< Stream: $chunk")
                    val json = Json.encodeToString(
                        StreamChunk.serializer(),
                        StreamChunk(
                            id = id,
                            choices = listOf(
                                StreamChoice(delta = Message("assistant", chunk))
                            )
                        )
                    )
                    outputStream.write("data: $json\n\n".toByteArray())
                    outputStream.flush()
                }

                // Send final chunk
                val finalJson = Json.encodeToString(
                    StreamChunk.serializer(),
                    StreamChunk(
                        id = id,
                        choices = listOf(
                            StreamChoice(
                                delta = Message("assistant", ""),
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
            val result = llmEngine.chat(userMessage, request.temperature)
            onDebug?.invoke("<<< Model Output: $result")

            val response = OpenAIResponse(
                id = "litert-${System.currentTimeMillis()}",
                choices = listOf(
                    Choice(message = Message("assistant", result))
                ),
                usage = Usage(
                    promptTokens = userMessage.length / 4,
                    completionTokens = result.length / 4,
                    totalTokens = (userMessage.length + result.length) / 4
                )
            )

            call.respond(response)
        }
    }
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
