package com.abandonedcart.recovery.e2e

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.analytics.KafkaAnalyticsPublisher
import com.abandonedcart.recovery.contract.AnalyticsEvent
import com.abandonedcart.recovery.contract.CartAbandonedEvent
import com.abandonedcart.recovery.contract.CartMutationEvent
import com.abandonedcart.recovery.contract.CartStateEvent
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
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
            val dueAttempt = attempts.minBy { it.scheduledAt }
            val firstAttempt = waitForAttemptExecutionOrExecute(dueAttempt.attemptId)
            assertNotNull(firstAttempt)
            assertEquals("SENT", firstAttempt.status)
            assertEquals("ALLOWED", firstAttempt.frequencyCapResult)

            val analyticsByType = waitForAnalyticsEventsForAttempt(
                analyticsConsumer,
                cartId,
                firstAttempt.attemptId,
                setOf("attempt_scheduled"),
            )
            val scheduled = analyticsByType["attempt_scheduled"]

            assertNotNull(scheduled)
            assertEquals(scheduled.attributes["experiment_id"], firstAttempt.experimentId)
            assertEquals(scheduled.attributes["variant_id"], firstAttempt.variantId)
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
            val dueAttempt = attempts.minBy { it.scheduledAt }

            val purchaseEvent = CartStateEvent(
                cartId = cartId,
                tenantId = "tenant-1",
                stateType = "purchase_completed",
                occurredAt = OffsetDateTime.now().toString(),
                terminalReference = "2",
            )
            kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, purchaseEvent)
            waitForState(cartId, "PURCHASED")

            val firstAttempt = waitForAttemptExecutionOrExecute(dueAttempt.attemptId)
            assertNotNull(firstAttempt)
            assertEquals("SUPPRESSED", firstAttempt.status)
            assertEquals("cart_purchased", firstAttempt.suppressionReason)

            val analyticsByType = waitForAnalyticsEventsForAttempt(
                analyticsConsumer,
                cartId,
                firstAttempt.attemptId,
                setOf("attempt_scheduled"),
            )
            val scheduled = analyticsByType["attempt_scheduled"]

            assertNotNull(scheduled)
        } finally {
            analyticsConsumer.close()
        }
    }

    @Test
    fun `identity stitched abandoned cart rebinds scheduled attempts before execution`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val anonymousId = "anon-${UUID.randomUUID()}"
        val knownUserId = "user-${UUID.randomUUID()}"

        publishCartMutation(cartId = cartId, anonymousId = anonymousId, userId = null)
        val anonymousState = waitForState(cartId, "ACTIVE")

        assertNotNull(anonymousState)
        assertEquals(anonymousId, anonymousState.anonymousId)
        assertEquals(null, anonymousState.userId)

        val abandonedEvent = CartAbandonedEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            anonymousId = anonymousId,
            userId = null,
            abandonedAt = OffsetDateTime.now().minusHours(24).plusSeconds(5).toString(),
        )
        kafkaProducer.publish(appConfig.recoveryCartAbandonedTopic, cartId, abandonedEvent)

        val scheduledBeforeStitch = waitForAttempts(cartId, expectedCount = 3)
        assertEquals(3, scheduledBeforeStitch.size)
        assertTrue(scheduledBeforeStitch.all { it.userId == null })

        val identityEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "identity_linked",
            occurredAt = OffsetDateTime.now().toString(),
            eventReference = "2",
            anonymousId = anonymousId,
            userId = knownUserId,
            stitchedIdentityJson = """{"linked":true,"source":"checkout"}""",
        )
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, identityEvent)

        val stitchedState = waitForState(cartId, expectedStatus = "ACTIVE", expectedUserId = knownUserId)
        assertNotNull(stitchedState)
        assertEquals(anonymousId, stitchedState.anonymousId)
        assertEquals(knownUserId, stitchedState.userId)
        assertEquals(2L, stitchedState.stateVersion)

        val reboundAttempts = waitForAttemptUserId(cartId, expectedCount = 3, expectedUserId = knownUserId)
        assertEquals(3, reboundAttempts.size)
        assertTrue(reboundAttempts.all { it.userId == knownUserId })

        val sentAttempt = waitForAttemptExecutionOrExecute(reboundAttempts.minBy { it.scheduledAt }.attemptId)

        assertNotNull(sentAttempt)
        assertEquals("SENT", sentAttempt.status)
        assertEquals(knownUserId, sentAttempt.userId)
        assertEquals(3, recoveryAttemptRepository.findByCartId(cartId).size)
    }

    @BeforeEach
    fun truncateTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("truncate table recovery_attempt, cart_recovery_state")
            }
        }
    }

    private fun publishCartMutation(
        cartId: String,
        stateVersion: String = "1",
        anonymousId: String? = null,
        userId: String? = "user-1",
    ) {
        val attributes = linkedMapOf(
            "stateVersion" to stateVersion,
            "cartSnapshotJson" to """{"items":["sku-1"]}""",
        )
        if (anonymousId != null) {
            attributes["anonymousId"] = anonymousId
        }
        if (userId != null) {
            attributes["userId"] = userId
        }
        val event = CartMutationEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = OffsetDateTime.now().toString(),
            attributes = attributes,
        )
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, event)
    }

    private fun waitForState(
        cartId: String,
        expectedStatus: String,
        expectedUserId: String? = null,
    ): com.abandonedcart.recovery.repository.CartRecoveryState? {
        repeat(80) {
            val state = cartRecoveryStateRepository.findByCartId(cartId)
            if (state != null && state.cartStatus == expectedStatus && (expectedUserId == null || state.userId == expectedUserId)) {
                return state
            }
            Thread.sleep(250)
        }
        return cartRecoveryStateRepository.findByCartId(cartId)
    }

    private fun waitForAttempts(cartId: String, expectedCount: Int): List<RecoveryAttempt> {
        repeat(80) {
            val attempts = recoveryAttemptRepository.findByCartId(cartId)
            if (attempts.size == expectedCount) {
                return attempts
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByCartId(cartId)
    }

    private fun waitForAttemptStatus(attemptId: String, expectedStatus: String): RecoveryAttempt? {
        repeat(80) {
            val attempt = recoveryAttemptRepository.findByAttemptId(attemptId)
            if (attempt != null && attempt.status == expectedStatus) {
                return attempt
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByAttemptId(attemptId)
    }

    private fun waitForAttemptUserId(cartId: String, expectedCount: Int, expectedUserId: String): List<RecoveryAttempt> {
        repeat(80) {
            val attempts = recoveryAttemptRepository.findByCartId(cartId)
            if (attempts.size == expectedCount && attempts.all { it.userId == expectedUserId }) {
                return attempts
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByCartId(cartId)
    }

    private fun waitForAnalyticsEvents(
        consumer: org.apache.kafka.clients.consumer.Consumer<String, String>,
        cartId: String,
        expectedCount: Int,
    ): List<AnalyticsEvent> {
        val events = mutableListOf<AnalyticsEvent>()
        repeat(80) {
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

    private fun waitForAnalyticsEventsForAttempt(
        consumer: org.apache.kafka.clients.consumer.Consumer<String, String>,
        cartId: String,
        attemptId: String,
        expectedTypes: Set<String>,
    ): Map<String, AnalyticsEvent> {
        val eventsByType = mutableMapOf<String, AnalyticsEvent>()
        repeat(80) {
            consumer.poll(Duration.ofMillis(500))
                .filter { it.key() == cartId }
                .map { jsonCodec.fromJson<AnalyticsEvent>(it.value()) }
                .filter { it.attemptId == attemptId && it.eventType in expectedTypes }
                .forEach { event ->
                    eventsByType.putIfAbsent(event.eventType, event)
                }
            if (expectedTypes.all { eventsByType.containsKey(it) }) {
                return eventsByType
            }
        }
        return eventsByType
    }

    private fun waitForAttemptExecutionOrExecute(attemptId: String): RecoveryAttempt {
        repeat(80) {
            val current = recoveryAttemptRepository.findByAttemptId(attemptId)
            if (current != null && current.status in TERMINAL_ATTEMPT_STATUSES) {
                return current
            }
            if (current != null && current.status == "SCHEDULED" && !current.scheduledAt.isAfter(OffsetDateTime.now())) {
                val claimed = recoveryAttemptRepository.claimDueAttempts(limit = 1, leaseDuration = Duration.ofMinutes(5))
                val claimedAttempt = claimed.singleOrNull { it.attemptId == attemptId }
                if (claimedAttempt != null) {
                    assertTrue(recoveryAttemptRepository.markDispatched(claimedAttempt.attemptId))
                    assertTrue(
                        dueAttemptExecutor.execute(
                            RecoveryAttemptDueEvent(
                                attemptId = claimedAttempt.attemptId,
                                cartId = claimedAttempt.cartId,
                                channel = claimedAttempt.channel,
                                templateKey = claimedAttempt.templateKey,
                                scheduledAt = claimedAttempt.scheduledAt.toString(),
                            ),
                        ),
                    )
                }
            }
            Thread.sleep(250)
        }
        return requireNotNull(recoveryAttemptRepository.findByAttemptId(attemptId))
    }

    companion object {
        private val topicSuffix = UUID.randomUUID().toString().take(8)
        private val appConfig = AppConfig.fromEnv(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                "KAFKA_CONSUMER_GROUP_ID" to "e2e-test-${UUID.randomUUID()}",
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
        private lateinit var cartRecoveryStateRepository: CartRecoveryStateRepository
        private lateinit var recoveryAttemptRepository: RecoveryAttemptRepository
        private lateinit var kafkaProducer: KafkaJsonProducer
        private lateinit var kafkaConsumer: KafkaLoggingConsumer
        private lateinit var dueAttemptDispatcher: DueAttemptDispatcher
        private lateinit var dueAttemptExecutor: DueAttemptExecutor
        private val TERMINAL_ATTEMPT_STATUSES = setOf("SENT", "SUPPRESSED", "FAILED")

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
            val stateEventProcessor = CartStateEventProcessor(cartRecoveryStateRepository, recoveryAttemptRepository)
            val analyticsPublisher = KafkaAnalyticsPublisher(appConfig, kafkaProducer)
            val recoveryPolicyService = RecoveryPolicyService(MockExperimentClient())
            val recoveryScheduler = RecoveryScheduler(
                cartRecoveryStateRepository,
                recoveryAttemptRepository,
                recoveryPolicyService,
                analyticsPublisher,
            )
            dueAttemptExecutor = DueAttemptExecutor(
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
