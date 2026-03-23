package com.abandonedcart.recovery.contract

data class PolicyResolvedEvent(
    val cartId: String,
    val policyId: String,
    val policyVersion: Int,
    val experimentId: String? = null,
    val variantId: String? = null,
    val channels: List<String> = emptyList(),
)

