package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.NewTopic

class KafkaTopicBootstrapper(
    private val config: AppConfig,
) {
    fun ensureTopics(): Int {
        KafkaClientFactory.createAdminClient(config).use { adminClient ->
            val existingTopics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS)
            val missingTopics = config.allKafkaTopics()
                .filterNot(existingTopics::contains)
                .map { topicName -> NewTopic(topicName, 1, 1.toShort()) }
            if (missingTopics.isEmpty()) {
                return 0
            }
            adminClient.createTopics(missingTopics).all().get(10, TimeUnit.SECONDS)
            return missingTopics.size
        }
    }
}

