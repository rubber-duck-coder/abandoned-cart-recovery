package com.abandonedcart.recovery.policy

data class ResolvedRecoveryPolicy(
    val experimentId: String,
    val experimentName: String,
    val variantId: String,
    val policyId: String,
    val policyVersion: Int,
    val touches: List<RecoveryTouch>,
)
