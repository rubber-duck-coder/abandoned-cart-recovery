package com.abandonedcart.recovery.policy

data class PolicyResolutionRequest(
    val cartId: String,
    val tenantId: String,
    val userId: String?,
    val anonymousId: String?,
)
