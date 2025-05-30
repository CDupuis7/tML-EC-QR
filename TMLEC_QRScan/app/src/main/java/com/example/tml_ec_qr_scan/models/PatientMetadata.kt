package com.example.tml_ec_qr_scan.models

data class PatientMetadata(
        val id: String = "",
        val age: Int = 0,
        val gender: String = "",
        val healthStatus: String = "",
        val additionalNotes: String = ""
)
