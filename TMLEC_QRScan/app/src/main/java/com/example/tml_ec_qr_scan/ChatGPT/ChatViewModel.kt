package com.example.tml_ec_qr_scan.ChatGPT

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _response = MutableStateFlow("Hello!")
    val response: StateFlow<String> = _response

    private val _chatLog = MutableStateFlow<List<DisplayMessage>>(emptyList())
    val chatLog: StateFlow<List<DisplayMessage>> = _chatLog


    data class DisplayMessage(
        val sender: String,
        val content: String
    )


    fun sendMessage(message: String) {
        viewModelScope.launch {
            _chatLog.value = _chatLog.value + DisplayMessage("user", message)

            try {
                val request = ChatRequest(messages = listOf(ChatMessage("user", message)))
                val result = RetrofitInstance.api.getChatCompletion(request)

                if (result.isSuccessful) {
                    val reply = result.body()?.choices?.firstOrNull()?.message?.content
                    if(reply != null) {
                        _response.value = reply
                    } else {
                        _response.value = "No response"
                    }

                } else {
                    "Error: ${result.code()}"
                }

                _chatLog.value = _chatLog.value + DisplayMessage("assistant", reply)

            } catch (e: Exception) {
                _chatLog.value = _chatLog.value + DisplayMessage("assistant", "Exception: ${e.message}")
            }
        }
    }
}