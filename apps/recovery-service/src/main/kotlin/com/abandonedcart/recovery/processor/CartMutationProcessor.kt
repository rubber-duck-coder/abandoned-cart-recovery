package com.abandonedcart.recovery.processor

import com.abandonedcart.recovery.contract.CartMutationEvent
import com.abandonedcart.recovery.repository.CartRecoveryState
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.CartRecoveryStateWriteResult
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

class CartMutationProcessor(
    private val repository: CartRecoveryStateRepository,
) {
    private val logger = LoggerFactory.getLogger(CartMutationProcessor::class.java)

    fun process(event: CartMutationEvent): CartRecoveryStateWriteResult {
        val existing = repository.findByCartId(event.cartId)
        val state = CartRecoveryState(
            cartId = event.cartId,
            tenantId = event.tenantId,
            anonymousId = event.attributes["anonymousId"] ?: existing?.anonymousId,
            userId = event.attributes["userId"] ?: existing?.userId,
            cartStatus = "ACTIVE",
            abandonmentStatus = existing?.abandonmentStatus ?: "ACTIVE",
            policyId = existing?.policyId,
            policyVersion = existing?.policyVersion,
            lastCartMutationAt = parseOffsetDateTime(event.occurredAt),
            lastCriticalEventAt = existing?.lastCriticalEventAt,
            lastPurchaseAt = existing?.lastPurchaseAt,
            stateVersion = event.attributes["stateVersion"]?.toLong() ?: 1L,
            cartSnapshotJson = event.attributes["cartSnapshotJson"] ?: existing?.cartSnapshotJson ?: """{"items":[]}""",
            stitchedIdentityJson = event.attributes["stitchedIdentityJson"] ?: existing?.stitchedIdentityJson,
            createdAt = existing?.createdAt,
            updatedAt = existing?.updatedAt,
        )
        val result = repository.upsert(state)
        logger.info("Processed cart mutation cartId={} mutationType={} writeResult={}", event.cartId, event.mutationType, result)
        return result
    }

    private fun parseOffsetDateTime(value: String): OffsetDateTime = OffsetDateTime.parse(value)
}

