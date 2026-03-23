package com.abandonedcart.recovery.executor

import com.abandonedcart.recovery.analytics.AnalyticsPublisher
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
import com.abandonedcart.recovery.eligibility.EligibilityEvaluator
import com.abandonedcart.recovery.frequencycap.FrequencyCapClient
import com.abandonedcart.recovery.notification.NotificationSender
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import java.time.Duration
import java.time.OffsetDateTime

class DueAttemptExecutor(
    private val recoveryAttemptRepository: RecoveryAttemptRepository,
    private val cartRecoveryStateRepository: CartRecoveryStateRepository,
    private val eligibilityEvaluator: EligibilityEvaluator,
    private val frequencyCapClient: FrequencyCapClient,
    private val notificationSender: NotificationSender,
    private val analyticsPublisher: AnalyticsPublisher,
) {
    fun execute(event: RecoveryAttemptDueEvent, leaseDuration: Duration = Duration.ofMinutes(5)): Boolean {
        val attempt = recoveryAttemptRepository.claimExecution(event.attemptId, leaseDuration) ?: return false
        val state = cartRecoveryStateRepository.findByCartId(event.cartId)
        val now = OffsetDateTime.now()
        val eligibilityDecision = eligibilityEvaluator.evaluate(state)

        if (!eligibilityDecision.eligible) {
            recoveryAttemptRepository.updateExecutionOutcome(
                attemptId = attempt.attemptId,
                status = "SUPPRESSED",
                executedAt = now,
                suppressionReason = eligibilityDecision.reason,
                frequencyCapResult = null,
                providerResultJson = """{"suppressed":true}""",
            )
            analyticsPublisher.publish(
                AnalyticsEvent(
                    eventType = "attempt_suppressed",
                    cartId = attempt.cartId,
                    attemptId = attempt.attemptId,
                    channel = attempt.channel,
                    occurredAt = now.toString(),
                    attributes = mapOf("reason" to (eligibilityDecision.reason ?: "unknown")),
                ),
            )
            return true
        }

        val capDecision = frequencyCapClient.evaluate(attempt)
        if (!capDecision.allowed) {
            recoveryAttemptRepository.updateExecutionOutcome(
                attemptId = attempt.attemptId,
                status = "SUPPRESSED",
                executedAt = now,
                suppressionReason = capDecision.reason ?: "frequency_capped",
                frequencyCapResult = "DENIED",
                providerResultJson = """{"suppressed":true}""",
            )
            analyticsPublisher.publish(
                AnalyticsEvent(
                    eventType = "attempt_suppressed",
                    cartId = attempt.cartId,
                    attemptId = attempt.attemptId,
                    channel = attempt.channel,
                    occurredAt = now.toString(),
                    attributes = mapOf("reason" to (capDecision.reason ?: "frequency_capped")),
                ),
            )
            return true
        }

        try {
            val sendResult = notificationSender.send(attempt)
            recoveryAttemptRepository.updateExecutionOutcome(
                attemptId = attempt.attemptId,
                status = "SENT",
                executedAt = now,
                suppressionReason = null,
                frequencyCapResult = "ALLOWED",
                providerResultJson = sendResult.payloadJson,
            )
            analyticsPublisher.publish(
                AnalyticsEvent(
                    eventType = "attempt_sent",
                    cartId = attempt.cartId,
                    attemptId = attempt.attemptId,
                    channel = attempt.channel,
                    occurredAt = now.toString(),
                    attributes = mapOf("provider_message_id" to sendResult.providerMessageId),
                ),
            )
        } catch (error: Exception) {
            recoveryAttemptRepository.updateExecutionOutcome(
                attemptId = attempt.attemptId,
                status = "FAILED",
                executedAt = now,
                suppressionReason = null,
                frequencyCapResult = "ALLOWED",
                providerResultJson = """{"error":"${error.message ?: "unknown"}"}""",
            )
            analyticsPublisher.publish(
                AnalyticsEvent(
                    eventType = "attempt_failed",
                    cartId = attempt.cartId,
                    attemptId = attempt.attemptId,
                    channel = attempt.channel,
                    occurredAt = now.toString(),
                    attributes = mapOf("reason" to (error.message ?: "unknown")),
                ),
            )
        }
        return true
    }
}
