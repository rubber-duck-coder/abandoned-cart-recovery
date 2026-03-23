package com.abandonedcart.recovery.repository

import java.time.OffsetDateTime

data class CartRecoveryState(
    val cartId: String,
    val tenantId: String,
    val anonymousId: String?,
    val userId: String?,
    val cartStatus: String,
    val abandonmentStatus: String,
    val policyId: String?,
    val policyVersion: Int?,
    val lastCartMutationAt: OffsetDateTime?,
    val lastCriticalEventAt: OffsetDateTime?,
    val lastPurchaseAt: OffsetDateTime?,
    val stateVersion: Long,
    val cartSnapshotJson: String,
    val stitchedIdentityJson: String?,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
)

