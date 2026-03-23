package com.abandonedcart.recovery

data class AppConfig(
    val appHost: String,
    val appPort: Int,
    val metricsHost: String,
    val metricsPort: Int,
    val kafkaBootstrapServers: String,
    val postgresJdbcUrl: String,
    val postgresUser: String,
    val postgresPassword: String,
    val postgresPoolSize: Int,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): AppConfig {
            return AppConfig(
                appHost = env.getOrDefault("APP_HOST", "0.0.0.0"),
                appPort = env.getOrDefault("APP_PORT", "8080").toInt(),
                metricsHost = env.getOrDefault("METRICS_HOST", "0.0.0.0"),
                metricsPort = env.getOrDefault("METRICS_PORT", "9464").toInt(),
                kafkaBootstrapServers = env.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                postgresJdbcUrl = env.getOrDefault("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/recovery"),
                postgresUser = env.getOrDefault("POSTGRES_USER", "recovery"),
                postgresPassword = env.getOrDefault("POSTGRES_PASSWORD", "recovery"),
                postgresPoolSize = env.getOrDefault("POSTGRES_POOL_SIZE", "5").toInt(),
            )
        }
    }
}
