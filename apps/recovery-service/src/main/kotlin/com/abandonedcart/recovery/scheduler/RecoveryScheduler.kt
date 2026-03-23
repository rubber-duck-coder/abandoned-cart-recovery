package com.abandonedcart.recovery.scheduler

import com.abandonedcart.recovery.analytics.AnalyticsPublisher
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.contract.CartAbandonedEvent
import com.abandonedcart.recovery.policy.PolicyResolutionRequest
import com.abandonedcart.recovery.policy.RecoveryPolicyService
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.RecoveryAttempt
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import org.slf4j.LoggerFactory

class RecoveryScheduler(
    private val cartRecoveryStateRepository: CartRecoveryStateRepository,
    private val recoveryAttemptRepository: RecoveryAttemptRepository,
    private val recoveryPolicyService: RecoveryPolicyService,
    private val analyticsPublisher: AnalyticsPublisher,
) {
    private val logger = LoggerFactory.getLogger(RecoveryScheduler::class.java)

    fun schedule(event: CartAbandonedEvent): Int {
        val state = cartRecoveryStateRepository.findByCartId(event.cartId)
        if (state == null) {
            logger.warn("Skipping abandoned cart without recovery state cartId={}", event.cartId)
            return 0
        }
        if (state.cartStatus in TERMINAL_CART_STATUSES) {
            logger.info("Skipping abandoned cart with terminal state cartId={} cartStatus={}", event.cartId, state.cartStatus)
            return 0
        }

        val resolvedPolicy = recoveryPolicyService.resolve(
            PolicyResolutionRequest(
                cartId = event.cartId,
                tenantId = event.tenantId,
                userId = state.userId ?: event.userId,
                anonymousId = state.anonymousId ?: event.anonymousId,
            ),
        )
        val abandonedAt = OffsetDateTime.parse(event.abandonedAt)

        return resolvedPolicy.touches.count { touch ->
            val scheduledAt = abandonedAt.plusHours(touch.delayHours)
            val attempt = RecoveryAttempt(
                attemptId = deterministicAttemptId(event.cartId, resolvedPolicy.policyVersion, touch.touchIndex, scheduledAt),
                cartId = event.cartId,
                tenantId = event.tenantId,
                userId = state.userId ?: event.userId,
                experimentId = resolvedPolicy.experimentId,
                experimentName = resolvedPolicy.experimentName,
                variantId = resolvedPolicy.variantId,
                policyId = resolvedPolicy.policyId,
                policyVersion = resolvedPolicy.policyVersion,
                touchIndex = touch.touchIndex,
                scheduledAt = scheduledAt,
                executedAt = null,
                channel = touch.channel,
                templateKey = touch.templateKey,
                status = "SCHEDULED",
                suppressionReason = null,
                sendIdempotencyKey = "${event.cartId}:${resolvedPolicy.policyVersion}:${touch.touchIndex}:${touch.channel}",
                frequencyCapResult = null,
                providerResultJson = null,
            )
            val created = recoveryAttemptRepository.schedule(attempt)
            if (created) {
                analyticsPublisher.publish(
                    AnalyticsEvent(
                        eventType = "attempt_scheduled",
                        cartId = event.cartId,
                        attemptId = attempt.attemptId,
                        channel = attempt.channel,
                        occurredAt = OffsetDateTime.now().toString(),
                        attributes = mapOf(
                            "experiment_id" to resolvedPolicy.experimentId,
                            "experiment_name" to resolvedPolicy.experimentName,
                            "variant_id" to resolvedPolicy.variantId,
                            "policy_id" to resolvedPolicy.policyId,
                            "policy_version" to resolvedPolicy.policyVersion.toString(),
                            "template_key" to attempt.templateKey,
                        ),
                    ),
                )
            }
            created
        }
    }

    private fun deterministicAttemptId(
        cartId: String,
        policyVersion: Int,
        touchIndex: Int,
        scheduledAt: OffsetDateTime,
    ): String {
        val key = "$cartId:$policyVersion:$touchIndex:${scheduledAt.toInstant()}"
        return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    companion object {
        private val TERMINAL_CART_STATUSES = setOf("PURCHASED", "DELETED")
    }
}
