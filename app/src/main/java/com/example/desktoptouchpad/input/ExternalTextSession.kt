package com.example.desktoptouchpad.input

data class ExternalTextSession(
    val sessionId: Long,
    val displayId: Int,
    val targetLabel: String,
    val hintText: String?,
    val refocusTrigger: Long = 0L,
)
