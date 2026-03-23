package com.abandonedcart.recovery.analytics

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.kafka.KafkaJsonProducer

class KafkaAnalyticsPublisher(
    private val appConfig: AppConfig,
    private val producer: KafkaJsonProducer,
) : AnalyticsPublisher {
    override fun publish(event: AnalyticsEvent) {
        producer.publish(appConfig.recoveryAnalyticsEventsTopic, event.cartId, event)
    }
}
