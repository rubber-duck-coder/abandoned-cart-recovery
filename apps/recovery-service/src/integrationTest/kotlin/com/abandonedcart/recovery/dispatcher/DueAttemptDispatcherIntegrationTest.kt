package com.abandonedcart.recovery.dispatcher

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
import com.abandonedcart.recovery.db.FlywayMigrator
import com.abandonedcart.recovery.kafka.KafkaClientFactory
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.repository.RecoveryAttempt
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

class DueAttemptDispatcherIntegrationTest {
    @Test
    fun `dispatcher claims due attempts publishes kafka work and does not duplicate on rerun`() {
        val attempt = sampleAttempt(
            attemptId = "attempt-${UUID.randomUUID()}",
            cartId = "cart-${UUID.randomUUID()}",
            scheduledAt = OffsetDateTime.now().minusMinutes(2),
        )
        val consumer = KafkaClientFactory.createStringConsumer(appConfig, "due-dispatch-test-${UUID.randomUUID()}")

        assertTrue(recoveryAttemptRepository.schedule(attempt))

        consumer.subscribe(listOf(appConfig.recoveryAttemptsTopic))
        consumer.poll(Duration.ofMillis(250))

        try {
            val firstDispatchCount = dispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))
            val secondDispatchCount = dispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val dueEvents = mutableListOf<RecoveryAttemptDueEvent>()
            repeat(10) {
                val records = consumer.poll(Duration.ofMillis(500))
                records
                    .filter { it.key() == attempt.cartId }
                    .map { jsonCodec.fromJson<RecoveryAttemptDueEvent>(it.value()) }
                    .forEach { dueEvents += it }
                if (dueEvents.isNotEmpty()) {
                    return@repeat
                }
            }

            val attempts = recoveryAttemptRepository.findByCartId(attempt.cartId)

            assertEquals(1, firstDispatchCount)
            assertEquals(0, secondDispatchCount)
            assertEquals(1, dueEvents.size)
            assertEquals(attempt.attemptId, dueEvents.first().attemptId)
            assertEquals("DISPATCHED", attempts.first().status)
            assertTrue(attempts.first().dispatchedAt != null)
        } finally {
            consumer.close()
        }
    }

    @BeforeEach
    fun truncateTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("truncate table recovery_attempt, cart_recovery_state")
            }
        }
    }

    companion object {
        private val topicSuffix = UUID.randomUUID().toString().take(8)
        private val appConfig = AppConfig.fromEnv(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                "KAFKA_CONSUMER_GROUP_ID" to "dispatcher-test-${UUID.randomUUID()}",
                "POSTGRES_JDBC_URL" to System.getenv().getOrDefault("TEST_POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/recovery"),
                "POSTGRES_USER" to System.getenv().getOrDefault("TEST_POSTGRES_USER", "recovery"),
                "POSTGRES_PASSWORD" to System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "recovery"),
                "TOPIC_COMMERCE_CART_EVENTS" to "test.$topicSuffix.commerce.cart-events",
                "TOPIC_RECOVERY_CART_MUTATIONS" to "test.$topicSuffix.recovery.cart-mutations",
                "TOPIC_RECOVERY_CART_STATE_EVENTS" to "test.$topicSuffix.recovery.cart-state-events",
                "TOPIC_RECOVERY_CART_ABANDONED" to "test.$topicSuffix.recovery.cart-abandoned",
                "TOPIC_RECOVERY_ATTEMPTS" to "test.$topicSuffix.recovery.recovery-attempts",
                "TOPIC_RECOVERY_ANALYTICS" to "test.$topicSuffix.recovery.analytics-events",
            ),
        )
        private val jsonCodec = JsonCodec()
        private lateinit var dataSource: HikariDataSource
        private lateinit var recoveryAttemptRepository: RecoveryAttemptRepository
        private lateinit var kafkaProducer: KafkaJsonProducer
        private lateinit var dispatcher: DueAttemptDispatcher

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = appConfig.postgresJdbcUrl
                    username = appConfig.postgresUser
                    password = appConfig.postgresPassword
                    maximumPoolSize = 2
                },
            )
            FlywayMigrator(dataSource).migrate()
            recoveryAttemptRepository = RecoveryAttemptRepository(dataSource)
            kafkaProducer = KafkaJsonProducer(appConfig, jsonCodec)
            KafkaTopicBootstrapper(appConfig).ensureTopics()
            dispatcher = DueAttemptDispatcher(recoveryAttemptRepository, kafkaProducer, appConfig.recoveryAttemptsTopic)
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            kafkaProducer.close()
            dataSource.close()
        }

        private fun sampleAttempt(
            attemptId: String,
            cartId: String,
            scheduledAt: OffsetDateTime,
        ): RecoveryAttempt {
            return RecoveryAttempt(
                attemptId = attemptId,
                cartId = cartId,
                tenantId = "tenant-1",
                userId = "user-1",
                experimentId = "exp-1",
                experimentName = "default-recovery",
                variantId = "control",
                policyId = "default",
                policyVersion = 1,
                touchIndex = 1,
                scheduledAt = scheduledAt,
                executedAt = null,
                channel = "push",
                templateKey = "push-default",
                status = "SCHEDULED",
                suppressionReason = null,
                sendIdempotencyKey = "send-$attemptId",
                frequencyCapResult = null,
                providerResultJson = null,
            )
        }
    }
}
