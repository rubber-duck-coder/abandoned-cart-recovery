package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.JsonCodec
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaJsonProducer(
    private val config: AppConfig,
    private val jsonCodec: JsonCodec,
) : AutoCloseable {
    private val producer: KafkaProducer<String, String> = KafkaClientFactory.createStringProducer(config)

    fun publish(topic: String, key: String, value: Any) {
        producer.send(ProducerRecord(topic, key, jsonCodec.toJson(value))).get()
    }

    fun publishRawJson(topic: String, key: String, json: String) {
        producer.send(ProducerRecord(topic, key, json)).get()
    }

    override fun close() {
        producer.flush()
        producer.close()
    }
}
