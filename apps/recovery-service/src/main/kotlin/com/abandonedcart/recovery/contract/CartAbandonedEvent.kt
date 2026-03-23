package com.abandonedcart.recovery.contract

data class CartAbandonedEvent(
    val cartId: String,
    val tenantId: String,
    val anonymousId: String? = null,
    val userId: String? = null,
    val abandonedAt: String,
)

