package com.abandonedcart.recovery.e2e

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.analytics.KafkaAnalyticsPublisher
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.contract.CartAbandonedEvent
import com.abandonedcart.recovery.contract.CartMutationEvent
import com.abandonedcart.recovery.contract.CartStateEvent
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.db.FlywayMigrator
import com.abandonedcart.recovery.dispatcher.DueAttemptDispatcher
import com.abandonedcart.recovery.eligibility.EligibilityEvaluator
import com.abandonedcart.recovery.executor.DueAttemptExecutor
import com.abandonedcart.recovery.experiment.MockExperimentClient
import com.abandonedcart.recovery.frequencycap.MockFrequencyCapClient
import com.abandonedcart.recovery.kafka.KafkaClientFactory
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaLoggingConsumer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.notification.MockNotificationSender
import com.abandonedcart.recovery.policy.RecoveryPolicyService
import com.abandonedcart.recovery.processor.CartMutationProcessor
import com.abandonedcart.recovery.processor.CartStateEventProcessor
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.RecoveryAttempt
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import com.abandonedcart.recovery.scheduler.RecoveryScheduler
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

class RecoveryFlowE2ETest {
    @Test
    fun `happy path flows from activity to schedule to send with analytics continuity`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "e2e-happy-${UUID.randomUUID()}")

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            publishCartMutation(cartId)
            waitForState(cartId, "ACTIVE")

            val abandonedEvent = CartAbandonedEvent(
                cartId = cartId,
                tenantId = "tenant-1",
                anonymousId = "anon-1",
                userId = "user-1",
                abandonedAt = OffsetDateTime.now().minusHours(25).toString(),
            )
            kafkaProducer.publish(appConfig.recoveryCartAbandonedTopic, cartId, abandonedEvent)

            val attempts = waitForAttempts(cartId, expectedCount = 3)
            assertEquals(3, attempts.size)

            dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val firstAttempt = waitForAttemptStatus(attempts.first().attemptId, "SENT")
            assertNotNull(firstAttempt)
            assertEquals("SENT", firstAttempt.status)
            assertEquals("ALLOWED", firstAttempt.frequencyCapResult)

            val analyticsEvents = waitForAnalyticsEvents(analyticsConsumer, cartId, expectedCount = 2)
            val scheduled = analyticsEvents.firstOrNull { it.eventType == "attempt_scheduled" && it.attemptId == firstAttempt.attemptId }
            val sent = analyticsEvents.firstOrNull { it.eventType == "attempt_sent" && it.attemptId == firstAttempt.attemptId }

            assertNotNull(scheduled)
            assertNotNull(sent)
            assertEquals(scheduled.attributes["experiment_id"], attempts.first().experimentId)
            assertEquals(scheduled.attributes["variant_id"], attempts.first().variantId)
            assertEquals("push", sent.channel)
        } finally {
            analyticsConsumer.close()
        }
    }

    @Test
    fun `purchase before dispatch suppresses the due attempt with matching analytics`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "e2e-suppress-${UUID.randomUUID()}")

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            publishCartMutation(cartId)
            waitForState(cartId, "ACTIVE")

            val abandonedEvent = CartAbandonedEvent(
                cartId = cartId,
                tenantId = "tenant-1",
                anonymousId = "anon-1",
                userId = "user-1",
                abandonedAt = OffsetDateTime.now().minusHours(25).toString(),
            )
            kafkaProducer.publish(appConfig.recoveryCartAbandonedTopic, cartId, abandonedEvent)

            val attempts = waitForAttempts(cartId, expectedCount = 3)
            assertEquals(3, attempts.size)

            val purchaseEvent = CartStateEvent(
                cartId = cartId,
                tenantId = "tenant-1",
                stateType = "purchase_completed",
                occurredAt = OffsetDateTime.now().toString(),
                terminalReference = "2",
            )
            kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, purchaseEvent)
            waitForState(cartId, "PURCHASED")

            dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val firstAttempt = waitForAttemptStatus(attempts.first().attemptId, "SUPPRESSED")
            assertNotNull(firstAttempt)
            assertEquals("cart_purchased", firstAttempt.suppressionReason)

            val analyticsEvents = waitForAnalyticsEvents(analyticsConsumer, cartId, expectedCount = 2)
            val scheduled = analyticsEvents.firstOrNull { it.eventType == "attempt_scheduled" && it.attemptId == firstAttempt.attemptId }
            val suppressed = analyticsEvents.firstOrNull { it.eventType == "attempt_suppressed" && it.attemptId == firstAttempt.attemptId }

            assertNotNull(scheduled)
            assertNotNull(suppressed)
            assertEquals("cart_purchased", suppressed.attributes["reason"])
        } finally {
            analyticsConsumer.close()
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

    private fun publishCartMutation(cartId: String) {
        val event = CartMutationEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = OffsetDateTime.now().toString(),
            attributes = mapOf(
                "stateVersion" to "1",
                "cartSnapshotJson" to """{"items":["sku-1"]}""",
                "userId" to "user-1",
            ),
        )
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, event)
    }

    private fun waitForState(cartId: String, expectedStatus: String): com.abandonedcart.recovery.repository.CartRecoveryState? {
        repeat(20) {
            val state = cartRecoveryStateRepository.findByCartId(cartId)
            if (state != null && state.cartStatus == expectedStatus) {
                return state
            }
            Thread.sleep(250)
        }
        return cartRecoveryStateRepository.findByCartId(cartId)
    }

    private fun waitForAttempts(cartId: String, expectedCount: Int): List<RecoveryAttempt> {
        repeat(20) {
            val attempts = recoveryAttemptRepository.findByCartId(cartId)
            if (attempts.size == expectedCount) {
                return attempts
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByCartId(cartId)
    }

    private fun waitForAttemptStatus(attemptId: String, expectedStatus: String): RecoveryAttempt? {
        repeat(20) {
            val attempt = recoveryAttemptRepository.findByAttemptId(attemptId)
            if (attempt != null && attempt.status == expectedStatus) {
                return attempt
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByAttemptId(attemptId)
    }

    private fun waitForAnalyticsEvents(
        consumer: org.apache.kafka.clients.consumer.Consumer<String, String>,
        cartId: String,
        expectedCount: Int,
    ): List<AnalyticsEvent> {
        val events = mutableListOf<AnalyticsEvent>()
        repeat(20) {
            val records = consumer.poll(Duration.ofMillis(500))
            records
                .filter { it.key() == cartId }
                .map { jsonCodec.fromJson<AnalyticsEvent>(it.value()) }
                .forEach { event ->
                    if (events.none { it.eventType == event.eventType && it.attemptId == event.attemptId }) {
                        events += event
                    }
                }
            if (events.size >= expectedCount) {
                return events
            }
        }
        return events
    }

    companion object {
        private val appConfig = AppConfig.fromEnv(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                "KAFKA_CONSUMER_GROUP_ID" to "e2e-test-${UUID.randomUUID()}",
                "POSTGRES_JDBC_URL" to System.getenv().getOrDefault("TEST_POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/recovery"),
                "POSTGRES_USER" to System.getenv().getOrDefault("TEST_POSTGRES_USER", "recovery"),
                "POSTGRES_PASSWORD" to System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "recovery"),
            ),
        )
        private val jsonCodec = JsonCodec()
        private lateinit var dataSource: HikariDataSource
        private lateinit var cartRecoveryStateRepository: CartRecoveryStateRepository
        private lateinit var recoveryAttemptRepository: RecoveryAttemptRepository
        private lateinit var kafkaProducer: KafkaJsonProducer
        private lateinit var kafkaConsumer: KafkaLoggingConsumer
        private lateinit var dueAttemptDispatcher: DueAttemptDispatcher

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
            cartRecoveryStateRepository = CartRecoveryStateRepository(dataSource)
            recoveryAttemptRepository = RecoveryAttemptRepository(dataSource)
            kafkaProducer = KafkaJsonProducer(appConfig, jsonCodec)

            val mutationProcessor = CartMutationProcessor(cartRecoveryStateRepository)
            val stateEventProcessor = CartStateEventProcessor(cartRecoveryStateRepository)
            val analyticsPublisher = KafkaAnalyticsPublisher(appConfig, kafkaProducer)
            val recoveryPolicyService = RecoveryPolicyService(MockExperimentClient())
            val recoveryScheduler = RecoveryScheduler(
                cartRecoveryStateRepository,
                recoveryAttemptRepository,
                recoveryPolicyService,
                analyticsPublisher,
            )
            val dueAttemptExecutor = DueAttemptExecutor(
                recoveryAttemptRepository,
                cartRecoveryStateRepository,
                EligibilityEvaluator(),
                MockFrequencyCapClient(),
                MockNotificationSender(),
                analyticsPublisher,
            )
            KafkaTopicBootstrapper(appConfig).ensureTopics()
            dueAttemptDispatcher = DueAttemptDispatcher(recoveryAttemptRepository, kafkaProducer, appConfig.recoveryAttemptsTopic)
            kafkaConsumer = KafkaLoggingConsumer(
                appConfig,
                jsonCodec,
                kafkaProducer,
                mutationProcessor,
                stateEventProcessor,
                recoveryScheduler,
                dueAttemptExecutor,
            )
            kafkaConsumer.start()
            Thread.sleep(500)
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            kafkaConsumer.close()
            dataSource.close()
        }
    }
}
