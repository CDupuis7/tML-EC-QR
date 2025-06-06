package com.example.tml_ec_qr_scan.ChatGPT

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _response = MutableStateFlow("Hello!")
    val response: StateFlow<String> = _response

    fun sendMessage(message: String) {
        viewModelScope.launch {
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
                    _response.value = "Error: ${result.code()}"
                }
            } catch (e: Exception) {
                _response.value = "Exception: ${e.message}"
            }
        }
    }
}