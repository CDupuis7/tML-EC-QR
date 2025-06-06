package com.example.tml_ec_qr_scan.ChatGPT

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.Interceptor

object RetrofitInstance {
    private const val BASE_URL = "https://api.openai.com/"

    private val key = "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 ".replace("\u00A0","").trim()

    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()// 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 
                .addHeader("Authorization", "Bearer $key")
                .build()
            chain.proceed(request)
        }).build()

    val api : ChatGPTApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatGPTApi::class.java)
    }
}