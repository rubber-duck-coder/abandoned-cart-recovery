package com.abandonedcart.recovery.frequencycap

data class FrequencyCapDecision(
    val allowed: Boolean,
    val reason: String? = null,
)
