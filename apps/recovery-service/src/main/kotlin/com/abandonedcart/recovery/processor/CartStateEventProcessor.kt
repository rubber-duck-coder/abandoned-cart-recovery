package com.abandonedcart.recovery.processor

import com.abandonedcart.recovery.contract.CartStateEvent
import com.abandonedcart.recovery.repository.CartRecoveryState
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.CartRecoveryStateWriteResult
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

class CartStateEventProcessor(
    private val repository: CartRecoveryStateRepository,
) {
    private val logger = LoggerFactory.getLogger(CartStateEventProcessor::class.java)

    fun process(event: CartStateEvent): CartRecoveryStateWriteResult {
        val existing = repository.findByCartId(event.cartId)
        val eventTime = parseOffsetDateTime(event.occurredAt)
        val normalizedStateType = event.stateType.lowercase()
        val cartStatus = when {
            normalizedStateType.contains("purchase") || normalizedStateType.contains("checkout_completed") -> "PURCHASED"
            normalizedStateType.contains("delete") || normalizedStateType.contains("empty") -> "DELETED"
            else -> existing?.cartStatus ?: "ACTIVE"
        }
        val abandonmentStatus = if (normalizedStateType == "cart_abandoned") "ABANDONED" else existing?.abandonmentStatus ?: "ACTIVE"
        val state = CartRecoveryState(
            cartId = event.cartId,
            tenantId = event.tenantId,
            anonymousId = existing?.anonymousId,
            userId = existing?.userId,
            cartStatus = cartStatus,
            abandonmentStatus = abandonmentStatus,
            policyId = existing?.policyId,
            policyVersion = existing?.policyVersion,
            lastCartMutationAt = existing?.lastCartMutationAt,
            lastCriticalEventAt = eventTime,
            lastPurchaseAt = if (cartStatus == "PURCHASED") eventTime else existing?.lastPurchaseAt,
            stateVersion = event.terminalReference?.toLongOrNull()
                ?: existing?.stateVersion?.plus(1)
                ?: 1L,
            cartSnapshotJson = existing?.cartSnapshotJson ?: """{"items":[]}""",
            stitchedIdentityJson = existing?.stitchedIdentityJson,
            createdAt = existing?.createdAt,
            updatedAt = existing?.updatedAt,
        )
        val result = repository.upsert(state)
        logger.info("Processed cart state event cartId={} stateType={} writeResult={}", event.cartId, event.stateType, result)
        return result
    }

    private fun parseOffsetDateTime(value: String): OffsetDateTime = OffsetDateTime.parse(value)
}

