package com.abandonedcart.recovery.repository

import java.time.OffsetDateTime

data class RecoveryAttempt(
    val attemptId: String,
    val cartId: String,
    val tenantId: String,
    val userId: String?,
    val experimentId: String?,
    val experimentName: String?,
    val variantId: String?,
    val policyId: String,
    val policyVersion: Int,
    val touchIndex: Int,
    val scheduledAt: OffsetDateTime,
    val executedAt: OffsetDateTime?,
    val channel: String,
    val templateKey: String,
    val status: String,
    val suppressionReason: String?,
    val sendIdempotencyKey: String,
    val frequencyCapResult: String?,
    val providerResultJson: String?,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
)

