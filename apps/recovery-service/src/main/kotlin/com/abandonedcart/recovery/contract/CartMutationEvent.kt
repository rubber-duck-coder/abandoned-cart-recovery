package com.abandonedcart.recovery.contract

data class CartMutationEvent(
    val cartId: String,
    val tenantId: String,
    val mutationType: String,
    val occurredAt: String,
    val attributes: Map<String, String> = emptyMap(),
)

