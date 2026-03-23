package com.abandonedcart.recovery.contract

data class RecoveryAttemptDueEvent(
    val attemptId: String,
    val cartId: String,
    val channel: String,
    val templateKey: String,
    val scheduledAt: String,
)

