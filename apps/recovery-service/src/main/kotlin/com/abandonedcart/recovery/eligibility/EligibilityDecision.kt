package com.abandonedcart.recovery.eligibility

data class EligibilityDecision(
    val eligible: Boolean,
    val reason: String? = null,
)
