package com.example.tml_ec_qr_scan.ChatGPT

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String = "gpt-3.5-turbo",
    val messages: List<ChatMessage>
)

data class ChatChoice(val message: ChatMessage)

data class ChatResponse(val choices: List<ChatChoice>)