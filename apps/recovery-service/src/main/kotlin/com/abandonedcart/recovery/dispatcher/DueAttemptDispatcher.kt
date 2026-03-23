package com.abandonedcart.recovery.dispatcher

import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import com.abandonedcart.recovery.telemetry.NoOpRecoveryMetrics
import com.abandonedcart.recovery.telemetry.RecoveryMetrics
import java.time.Duration
import org.slf4j.LoggerFactory

class DueAttemptDispatcher(
    private val recoveryAttemptRepository: RecoveryAttemptRepository,
    private val kafkaJsonProducer: KafkaJsonProducer,
    private val recoveryAttemptsTopic: String,
    private val recoveryMetrics: RecoveryMetrics = NoOpRecoveryMetrics,
) {
    private val logger = LoggerFactory.getLogger(DueAttemptDispatcher::class.java)

    fun dispatchDueAttempts(limit: Int = 50, leaseDuration: Duration = Duration.ofMinutes(5)): Int {
        val startedAt = System.nanoTime()
        var outcome = "success"
        val claimed = recoveryAttemptRepository.claimDueAttempts(limit, leaseDuration)
        recoveryMetrics.recordDispatchBatch(claimed.size.toLong())
        claimed.forEach { attempt ->
            kafkaJsonProducer.publish(
                recoveryAttemptsTopic,
                attempt.cartId,
                RecoveryAttemptDueEvent(
                    attemptId = attempt.attemptId,
                    cartId = attempt.cartId,
                    channel = attempt.channel,
                    templateKey = attempt.templateKey,
                    scheduledAt = attempt.scheduledAt.toString(),
                ),
            )
            val marked = recoveryAttemptRepository.markDispatched(attempt.attemptId)
            if (!marked) {
                outcome = "partial"
                recoveryMetrics.recordAttemptDispatched(attempt.channel, "mark_failed")
                logger.warn("Failed to mark dispatched attemptId={}", attempt.attemptId)
            } else {
                recoveryMetrics.recordAttemptDispatched(attempt.channel, "success")
            }
        }
        recoveryMetrics.recordStageDuration("dispatcher", outcome, elapsedMs(startedAt))
        return claimed.size
    }

    private fun elapsedMs(startedAt: Long): Double = (System.nanoTime() - startedAt) / 1_000_000.0
}
