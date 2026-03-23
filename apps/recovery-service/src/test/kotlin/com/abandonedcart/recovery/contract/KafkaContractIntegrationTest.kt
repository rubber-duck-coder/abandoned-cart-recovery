package com.abandonedcart.recovery.contract

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.kafka.KafkaClientFactory
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaContractIntegrationTest {
    private val appConfig = AppConfig.fromEnv(
        mapOf(
            "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
            "KAFKA_CONSUMER_GROUP_ID" to "contract-test-${UUID.randomUUID()}",
        ),
    )
    private val jsonCodec = JsonCodec()

    @Test
    fun `topic bootstrap creates missing topics and producer consumer round trips json`() {
        val topicsCreated = KafkaTopicBootstrapper(appConfig).ensureTopics()
        val groupId = "contract-consumer-${UUID.randomUUID()}"
        val consumer = KafkaClientFactory.createStringConsumer(appConfig, groupId)
        val producer = KafkaJsonProducer(appConfig, jsonCodec)
        val event = CartMutationEvent(
            cartId = "cart-123",
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = "2026-03-23T14:01:00Z",
            attributes = mapOf("sku" to "sku-123"),
        )

        try {
            consumer.subscribe(listOf(appConfig.commerceCartEventsTopic))
            consumer.poll(Duration.ofMillis(250))
            producer.publish(appConfig.commerceCartEventsTopic, event.cartId, event)
            var receivedJson: String? = null
            repeat(10) {
                val records = consumer.poll(Duration.ofMillis(500))
                val match = records.firstOrNull { record -> record.key() == event.cartId }
                if (match != null) {
                    receivedJson = match.value()
                }
            }

            assertTrue(topicsCreated >= 0)
            assertTrue(receivedJson != null)
            val decoded = jsonCodec.fromJson<CartMutationEvent>(receivedJson!!)
            assertEquals("cart-123", decoded.cartId)
            assertEquals("item_added", decoded.mutationType)
        } finally {
            producer.close()
            consumer.close()
        }
    }
}
