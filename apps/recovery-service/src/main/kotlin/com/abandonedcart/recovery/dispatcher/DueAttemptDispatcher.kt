package com.abandonedcart.recovery.dispatcher

import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import java.time.Duration
import org.slf4j.LoggerFactory

class DueAttemptDispatcher(
    private val recoveryAttemptRepository: RecoveryAttemptRepository,
    private val kafkaJsonProducer: KafkaJsonProducer,
    private val recoveryAttemptsTopic: String,
) {
    private val logger = LoggerFactory.getLogger(DueAttemptDispatcher::class.java)

    fun dispatchDueAttempts(limit: Int = 50, leaseDuration: Duration = Duration.ofMinutes(5)): Int {
        val claimed = recoveryAttemptRepository.claimDueAttempts(limit, leaseDuration)
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
                logger.warn("Failed to mark dispatched attemptId={}", attempt.attemptId)
            }
        }
        return claimed.size
    }
}
