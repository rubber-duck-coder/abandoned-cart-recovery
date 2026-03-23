package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import java.util.Properties
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

object KafkaClientFactory {
    fun createAdminClient(config: AppConfig): AdminClient {
        val properties = Properties()
        properties[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = config.kafkaBootstrapServers
        return AdminClient.create(properties)
    }

    fun createStringProducer(config: AppConfig): KafkaProducer<String, String> {
        val properties = Properties()
        properties[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = config.kafkaBootstrapServers
        properties[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        properties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        properties[ProducerConfig.ACKS_CONFIG] = "all"
        return KafkaProducer(properties)
    }

    fun createStringConsumer(config: AppConfig, groupId: String = config.kafkaConsumerGroupId): KafkaConsumer<String, String> {
        val properties = Properties()
        properties[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = config.kafkaBootstrapServers
        properties[ConsumerConfig.GROUP_ID_CONFIG] = groupId
        properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "true"
        return KafkaConsumer(properties)
    }
}

