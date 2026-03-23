package com.abandonedcart.recovery

data class AppConfig(
    val appHost: String,
    val appPort: Int,
    val metricsHost: String,
    val metricsPort: Int,
    val kafkaBootstrapServers: String,
    val kafkaConsumerGroupId: String,
    val commerceCartEventsTopic: String,
    val recoveryCartMutationsTopic: String,
    val recoveryCartStateEventsTopic: String,
    val recoveryCartAbandonedTopic: String,
    val recoveryAttemptsTopic: String,
    val recoveryAnalyticsEventsTopic: String,
    val postgresJdbcUrl: String,
    val postgresUser: String,
    val postgresPassword: String,
    val postgresPoolSize: Int,
) {
    fun allKafkaTopics(): List<String> {
        return listOf(
            commerceCartEventsTopic,
            recoveryCartMutationsTopic,
            recoveryCartStateEventsTopic,
            recoveryCartAbandonedTopic,
            recoveryAttemptsTopic,
            recoveryAnalyticsEventsTopic,
        )
    }

    companion object {
        fun fromEnv(env: Map<String, String>): AppConfig {
            return AppConfig(
                appHost = env.getOrDefault("APP_HOST", "0.0.0.0"),
                appPort = env.getOrDefault("APP_PORT", "8080").toInt(),
                metricsHost = env.getOrDefault("METRICS_HOST", "0.0.0.0"),
                metricsPort = env.getOrDefault("METRICS_PORT", "9464").toInt(),
                kafkaBootstrapServers = env.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                kafkaConsumerGroupId = env.getOrDefault("KAFKA_CONSUMER_GROUP_ID", "recovery-service"),
                commerceCartEventsTopic = env.getOrDefault("TOPIC_COMMERCE_CART_EVENTS", "commerce.cart-events"),
                recoveryCartMutationsTopic = env.getOrDefault("TOPIC_RECOVERY_CART_MUTATIONS", "recovery.cart-mutations"),
                recoveryCartStateEventsTopic = env.getOrDefault("TOPIC_RECOVERY_CART_STATE_EVENTS", "recovery.cart-state-events"),
                recoveryCartAbandonedTopic = env.getOrDefault("TOPIC_RECOVERY_CART_ABANDONED", "recovery.cart-abandoned"),
                recoveryAttemptsTopic = env.getOrDefault("TOPIC_RECOVERY_ATTEMPTS", "recovery.recovery-attempts"),
                recoveryAnalyticsEventsTopic = env.getOrDefault("TOPIC_RECOVERY_ANALYTICS", "recovery.analytics-events"),
                postgresJdbcUrl = env.getOrDefault("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/recovery"),
                postgresUser = env.getOrDefault("POSTGRES_USER", "recovery"),
                postgresPassword = env.getOrDefault("POSTGRES_PASSWORD", "recovery"),
                postgresPoolSize = env.getOrDefault("POSTGRES_POOL_SIZE", "5").toInt(),
            )
        }
    }
}
