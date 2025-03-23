package com.example.tml_ec_qr_scan

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

// Define the model that will be sent as JSON
data class QRDataRequest(val qr_data: List<Pair<String,String>>) //List instead of single string
data class QRResponse(val qr_data: List<Pair<String,String>>?)

// Defining the API service interface
interface QRApiService {
    @POST("scan_qr/")
    fun sendQrData(@Body qrDataRequest: QRDataRequest): Call<QRResponse>
}