package com.abandonedcart.recovery.contract

data class CartStateEvent(
    val cartId: String,
    val tenantId: String,
    val stateType: String,
    val occurredAt: String,
    val terminalReference: String? = null,
)

