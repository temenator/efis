package com.example.efis


data class EfisState(
    val status: String = "Idle",
    val bytesPerSec: Int = 0,
    val msgsPerSec: Int = 0,
    val hbCount: Int = 0,
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f
)
