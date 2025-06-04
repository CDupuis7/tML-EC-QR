package com.example.tml_ec_qr_scan

// OpenAIService.kt

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAIService {
    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>
}

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null
)

data class Message(
    val role: String,
    val content: String? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    val function_call: FunctionCall? = null
)

data class Tool(
    val type: String = "function",
    val function: FunctionSchema
)

data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, Property>,
    val required: List<String>,
    val additionalProperties: Boolean = false
)

data class Property(
    val type: String,
    val description: String
)

data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: Message, val finish_reason: String?)
data class FunctionCall(val name: String, val arguments: String)

