package com.abandonedcart.recovery.contract

data class AnalyticsEvent(
    val eventType: String,
    val cartId: String,
    val attemptId: String? = null,
    val channel: String? = null,
    val occurredAt: String,
    val attributes: Map<String, String> = emptyMap(),
)

