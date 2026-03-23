package com.abandonedcart.recovery.analytics

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.telemetry.NoOpRecoveryMetrics
import com.abandonedcart.recovery.telemetry.RecoveryMetrics

class KafkaAnalyticsPublisher(
    private val appConfig: AppConfig,
    private val producer: KafkaJsonProducer,
    private val recoveryMetrics: RecoveryMetrics = NoOpRecoveryMetrics,
) : AnalyticsPublisher {
    override fun publish(event: AnalyticsEvent) {
        var result = "success"
        try {
            producer.publish(appConfig.recoveryAnalyticsEventsTopic, event.cartId, event)
        } catch (error: Exception) {
            result = "error"
            throw error
        } finally {
            recoveryMetrics.recordAnalyticsPublished(event.eventType, result)
        }
    }
}
