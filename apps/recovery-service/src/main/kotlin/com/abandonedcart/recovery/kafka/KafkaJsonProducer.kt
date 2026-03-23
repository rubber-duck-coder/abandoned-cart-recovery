package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.telemetry.NoOpRecoveryMetrics
import com.abandonedcart.recovery.telemetry.RecoveryMetrics
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaJsonProducer(
    private val config: AppConfig,
    private val jsonCodec: JsonCodec,
    private val recoveryMetrics: RecoveryMetrics = NoOpRecoveryMetrics,
) : AutoCloseable {
    private val producer: KafkaProducer<String, String> = KafkaClientFactory.createStringProducer(config)

    fun publish(topic: String, key: String, value: Any) {
        publishJson(topic, key, jsonCodec.toJson(value))
    }

    fun publishRawJson(topic: String, key: String, json: String) {
        publishJson(topic, key, json)
    }

    override fun close() {
        producer.flush()
        producer.close()
    }

    private fun publishJson(topic: String, key: String, json: String) {
        val startedAt = System.nanoTime()
        var result = "success"
        try {
            producer.send(ProducerRecord(topic, key, json)).get()
        } catch (error: Exception) {
            result = "error"
            throw error
        } finally {
            recoveryMetrics.recordKafkaPublished(topic, result, elapsedMs(startedAt))
        }
    }

    private fun elapsedMs(startedAt: Long): Double = (System.nanoTime() - startedAt) / 1_000_000.0
}
