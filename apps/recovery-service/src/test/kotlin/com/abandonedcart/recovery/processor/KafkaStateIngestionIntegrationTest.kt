package com.abandonedcart.recovery.processor

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
import com.abandonedcart.recovery.frequencycap.MockFrequencyCapClient
import com.abandonedcart.recovery.kafka.KafkaClientFactory
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaLoggingConsumer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.notification.MockNotificationSender
import com.abandonedcart.recovery.policy.RecoveryPolicyService
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.CartRecoveryStateWriteResult
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

class KafkaStateIngestionIntegrationTest {
    @Test
    fun `commerce mutation event is routed and persisted to recovery state`() {
        val cartId = "cart-${UUID.randomUUID()}"
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

        val state = waitForState(cartId)
        assertNotNull(state)
        assertEquals("ACTIVE", state.cartStatus)
        assertTrue(state.cartSnapshotJson.contains("sku-1"))
    }

    @Test
    fun `terminal state event blocks later mutation rewrite`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val purchaseEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "purchase_completed",
            occurredAt = OffsetDateTime.now().toString(),
            terminalReference = "2",
        )
        val lateMutation = CartMutationEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = OffsetDateTime.now().plusMinutes(1).toString(),
            attributes = mapOf(
                "stateVersion" to "3",
                "cartSnapshotJson" to """{"items":["sku-late"]}""",
            ),
        )

        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, purchaseEvent)
        waitForState(cartId, expectedStatus = "PURCHASED")
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, lateMutation)

        val state = waitForState(cartId, expectedStatus = "PURCHASED")
        assertNotNull(state)
        assertEquals("PURCHASED", state.cartStatus)
        assertTrue(state.cartSnapshotJson.contains("items"))
        assertEquals(2L, state.stateVersion)
    }

    @Test
    fun `identity linked event stitches anonymous cart to known user`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val mutationEvent = CartMutationEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = OffsetDateTime.now().toString(),
            attributes = mapOf(
                "stateVersion" to "1",
                "cartSnapshotJson" to """{"items":["sku-1"]}""",
                "anonymousId" to "anon-stitched",
            ),
        )
        val identityEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "identity_linked",
            occurredAt = OffsetDateTime.now().plusSeconds(5).toString(),
            eventReference = "2",
            anonymousId = "anon-stitched",
            userId = "user-stitched",
            stitchedIdentityJson = """{"linked":true}""",
        )

        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, mutationEvent)
        waitForState(cartId)
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, identityEvent)

        val state = waitForState(cartId, expectedUserId = "user-stitched", expectedVersion = 2L)
        assertNotNull(state)
        assertEquals("ACTIVE", state.cartStatus)
        assertEquals("anon-stitched", state.anonymousId)
        assertEquals("user-stitched", state.userId)
        assertEquals(2L, state.stateVersion)
        assertTrue(state.stitchedIdentityJson?.contains("linked") == true)
    }

    @Test
    fun `duplicate identity linked event is idempotent`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val identityEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "identity_linked",
            occurredAt = OffsetDateTime.now().toString(),
            eventReference = "1",
            anonymousId = "anon-dup",
            userId = "user-dup",
            stitchedIdentityJson = """{"linked":true}""",
        )

        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, identityEvent)
        val stateAfterFirst = waitForState(cartId, expectedUserId = "user-dup", expectedVersion = 1L)
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, identityEvent)
        val stateAfterSecond = waitForState(cartId, expectedUserId = "user-dup", expectedVersion = 1L)

        assertNotNull(stateAfterFirst)
        assertNotNull(stateAfterSecond)
        assertEquals(1L, stateAfterSecond.stateVersion)
        assertEquals("user-dup", stateAfterSecond.userId)
        assertEquals(stateAfterFirst.updatedAt, stateAfterSecond.updatedAt)
    }

    @Test
    fun `identity linked event updates terminal cart identity without reopening cart`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val purchaseEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "purchase_completed",
            occurredAt = OffsetDateTime.now().toString(),
            terminalReference = "2",
        )
        val identityEvent = CartStateEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            stateType = "identity_linked",
            occurredAt = OffsetDateTime.now().plusSeconds(5).toString(),
            eventReference = "3",
            anonymousId = "anon-terminal",
            userId = "user-terminal",
            stitchedIdentityJson = """{"linked":true}""",
        )

        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, purchaseEvent)
        waitForState(cartId, expectedStatus = "PURCHASED")
        kafkaProducer.publish(appConfig.commerceCartEventsTopic, cartId, identityEvent)

        val state = waitForState(cartId, expectedStatus = "PURCHASED", expectedUserId = "user-terminal", expectedVersion = 3L)
        assertNotNull(state)
        assertEquals("PURCHASED", state.cartStatus)
        assertEquals("user-terminal", state.userId)
        assertTrue(state.stitchedIdentityJson?.contains("linked") == true)
        assertEquals(3L, state.stateVersion)
    }

    @Test
    fun `abandoned cart event creates attempts once and emits scheduling analytics`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val activeState = com.abandonedcart.recovery.repository.CartRecoveryState(
            cartId = cartId,
            tenantId = "tenant-1",
            anonymousId = "anon-1",
            userId = "user-1",
            cartStatus = "ACTIVE",
            abandonmentStatus = "ACTIVE",
            policyId = null,
            policyVersion = null,
            lastCartMutationAt = OffsetDateTime.now(),
            lastCriticalEventAt = OffsetDateTime.now(),
            lastPurchaseAt = null,
            stateVersion = 1,
            cartSnapshotJson = """{"items":["sku-1"]}""",
            stitchedIdentityJson = """{"stitched":true}""",
        )
        val abandonedAt = OffsetDateTime.now().minusMinutes(5)
        val event = CartAbandonedEvent(
            cartId = cartId,
            tenantId = "tenant-1",
            anonymousId = "anon-1",
            userId = "user-1",
            abandonedAt = abandonedAt.toString(),
        )
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "analytics-test-${UUID.randomUUID()}")

        assertEquals(CartRecoveryStateWriteResult.APPLIED, repository.upsert(activeState))

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            kafkaProducer.publish(appConfig.recoveryCartAbandonedTopic, cartId, event)
            kafkaProducer.publish(appConfig.recoveryCartAbandonedTopic, cartId, event)

            val attempts = waitForAttempts(cartId, expectedCount = 3)
            assertEquals(3, attempts.size)
            assertEquals(listOf("push", "sms", "email"), attempts.map { it.channel })
            assertTrue(attempts.all { it.experimentId != null })
            assertTrue(attempts.all { it.variantId != null })

            val analyticsEvents = waitForAnalyticsEvents(analyticsConsumer, cartId, expectedCount = 3)
            assertEquals(3, analyticsEvents.size)
            assertTrue(analyticsEvents.all { it.eventType == "attempt_scheduled" })
        } finally {
            analyticsConsumer.close()
        }
    }

    @Test
    fun `due attempt is sent for eligible cart`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val activeState = sampleCartState(cartId = cartId, cartStatus = "ACTIVE", stateVersion = 1)
        val attempt = sampleAttempt(
            attemptId = "attempt-${UUID.randomUUID()}",
            cartId = cartId,
            scheduledAt = OffsetDateTime.now().minusMinutes(2),
        )
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "analytics-send-test-${UUID.randomUUID()}")

        assertEquals(CartRecoveryStateWriteResult.APPLIED, repository.upsert(activeState))
        assertTrue(recoveryAttemptRepository.schedule(attempt))

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val stored = waitForAttemptStatus(attempt.attemptId, expectedStatus = "SENT")
            assertNotNull(stored)
            assertEquals("SENT", stored.status)
            assertEquals("ALLOWED", stored.frequencyCapResult)
            assertTrue(stored.providerResultJson?.contains("mock") == true)

            val analyticsEvent = waitForAnalyticsEvent(analyticsConsumer, cartId, "attempt_sent")
            assertNotNull(analyticsEvent)
            assertEquals("attempt_sent", analyticsEvent.eventType)
            assertEquals(attempt.attemptId, analyticsEvent.attemptId)
        } finally {
            analyticsConsumer.close()
        }
    }

    @Test
    fun `due attempt is suppressed for purchased cart`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val purchasedState = sampleCartState(
            cartId = cartId,
            cartStatus = "PURCHASED",
            stateVersion = 2,
            lastPurchaseAt = OffsetDateTime.now(),
        )
        val attempt = sampleAttempt(
            attemptId = "attempt-${UUID.randomUUID()}",
            cartId = cartId,
            scheduledAt = OffsetDateTime.now().minusMinutes(2),
        )

        assertEquals(CartRecoveryStateWriteResult.APPLIED, repository.upsert(purchasedState))
        assertTrue(recoveryAttemptRepository.schedule(attempt))

        dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

        val stored = waitForAttemptStatus(attempt.attemptId, expectedStatus = "SUPPRESSED")
        assertNotNull(stored)
        assertEquals("SUPPRESSED", stored.status)
        assertEquals("cart_purchased", stored.suppressionReason)
    }

    @Test
    fun `due attempt is suppressed by frequency cap`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val activeState = sampleCartState(cartId = cartId, cartStatus = "ACTIVE", stateVersion = 1)
        val attempt = sampleAttempt(
            attemptId = "attempt-${UUID.randomUUID()}",
            cartId = cartId,
            scheduledAt = OffsetDateTime.now().minusMinutes(2),
            templateKey = "push-cap-denied",
        )
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "analytics-cap-test-${UUID.randomUUID()}")

        assertEquals(CartRecoveryStateWriteResult.APPLIED, repository.upsert(activeState))
        assertTrue(recoveryAttemptRepository.schedule(attempt))

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val stored = waitForAttemptStatus(attempt.attemptId, expectedStatus = "SUPPRESSED")
            assertNotNull(stored)
            assertEquals("SUPPRESSED", stored.status)
            assertEquals("DENIED", stored.frequencyCapResult)
            assertEquals("frequency_cap_denied", stored.suppressionReason)

            val analyticsEvent = waitForAnalyticsEvent(analyticsConsumer, cartId, "attempt_suppressed")
            assertNotNull(analyticsEvent)
            assertEquals("frequency_cap_denied", analyticsEvent.attributes["reason"])
        } finally {
            analyticsConsumer.close()
        }
    }

    @Test
    fun `due attempt is marked failed when provider send errors`() {
        val cartId = "cart-${UUID.randomUUID()}"
        val activeState = sampleCartState(cartId = cartId, cartStatus = "ACTIVE", stateVersion = 1)
        val attempt = sampleAttempt(
            attemptId = "attempt-${UUID.randomUUID()}",
            cartId = cartId,
            scheduledAt = OffsetDateTime.now().minusMinutes(2),
            templateKey = "push-provider-fail",
        )
        val analyticsConsumer = KafkaClientFactory.createStringConsumer(appConfig, "analytics-fail-test-${UUID.randomUUID()}")

        assertEquals(CartRecoveryStateWriteResult.APPLIED, repository.upsert(activeState))
        assertTrue(recoveryAttemptRepository.schedule(attempt))

        analyticsConsumer.subscribe(listOf(appConfig.recoveryAnalyticsEventsTopic))
        analyticsConsumer.poll(Duration.ofMillis(250))

        try {
            dueAttemptDispatcher.dispatchDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))

            val stored = waitForAttemptStatus(attempt.attemptId, expectedStatus = "FAILED")
            assertNotNull(stored)
            assertEquals("FAILED", stored.status)
            assertEquals("ALLOWED", stored.frequencyCapResult)
            assertTrue(stored.providerResultJson?.contains("mock_provider_failure") == true)

            val analyticsEvent = waitForAnalyticsEvent(analyticsConsumer, cartId, "attempt_failed")
            assertNotNull(analyticsEvent)
            assertEquals("mock_provider_failure", analyticsEvent.attributes["reason"])
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

    private fun waitForState(
        cartId: String,
        expectedStatus: String? = null,
        expectedUserId: String? = null,
        expectedVersion: Long? = null,
    ): com.abandonedcart.recovery.repository.CartRecoveryState? {
        repeat(40) {
            val state = repository.findByCartId(cartId)
            if (
                state != null &&
                (expectedStatus == null || state.cartStatus == expectedStatus) &&
                (expectedUserId == null || state.userId == expectedUserId) &&
                (expectedVersion == null || state.stateVersion == expectedVersion)
            ) {
                return state
            }
            Thread.sleep(250)
        }
        return repository.findByCartId(cartId)
    }

    private fun waitForAttempts(cartId: String, expectedCount: Int): List<com.abandonedcart.recovery.repository.RecoveryAttempt> {
        repeat(40) {
            val attempts = recoveryAttemptRepository.findByCartId(cartId)
            if (attempts.size == expectedCount) {
                return attempts
            }
            Thread.sleep(250)
        }
        return recoveryAttemptRepository.findByCartId(cartId)
    }

    private fun waitForAttemptStatus(attemptId: String, expectedStatus: String): RecoveryAttempt? {
        repeat(40) {
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
        repeat(40) {
            val records = consumer.poll(Duration.ofMillis(500))
            records
                .filter { it.key() == cartId }
                .map { jsonCodec.fromJson<AnalyticsEvent>(it.value()) }
                .forEach { events += it }
            if (events.size >= expectedCount) {
                return events
            }
        }
        return events
    }

    private fun waitForAnalyticsEvent(
        consumer: org.apache.kafka.clients.consumer.Consumer<String, String>,
        cartId: String,
        eventType: String,
    ): AnalyticsEvent? {
        repeat(40) {
            val records = consumer.poll(Duration.ofMillis(500))
            val event = records
                .filter { it.key() == cartId }
                .map { jsonCodec.fromJson<AnalyticsEvent>(it.value()) }
                .firstOrNull { it.eventType == eventType }
            if (event != null) {
                return event
            }
        }
        return null
    }

    companion object {
        private val topicSuffix = UUID.randomUUID().toString().take(8)
        private val appConfig = AppConfig.fromEnv(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                "KAFKA_CONSUMER_GROUP_ID" to "processor-test-${UUID.randomUUID()}",
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
        private lateinit var repository: CartRecoveryStateRepository
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
            repository = CartRecoveryStateRepository(dataSource)
            recoveryAttemptRepository = RecoveryAttemptRepository(dataSource)
            kafkaProducer = KafkaJsonProducer(appConfig, jsonCodec)
            val mutationProcessor = CartMutationProcessor(repository)
            val stateEventProcessor = CartStateEventProcessor(repository, recoveryAttemptRepository)
            val recoveryPolicyService = RecoveryPolicyService(com.abandonedcart.recovery.experiment.MockExperimentClient())
            val analyticsPublisher = KafkaAnalyticsPublisher(appConfig, kafkaProducer)
            val recoveryScheduler = RecoveryScheduler(repository, recoveryAttemptRepository, recoveryPolicyService, analyticsPublisher)
            val dueAttemptExecutor = DueAttemptExecutor(
                recoveryAttemptRepository,
                repository,
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

        private fun sampleCartState(
            cartId: String,
            cartStatus: String,
            stateVersion: Long,
            lastPurchaseAt: OffsetDateTime? = null,
        ): com.abandonedcart.recovery.repository.CartRecoveryState {
            val now = OffsetDateTime.now()
            return com.abandonedcart.recovery.repository.CartRecoveryState(
                cartId = cartId,
                tenantId = "tenant-1",
                anonymousId = "anon-1",
                userId = "user-1",
                cartStatus = cartStatus,
                abandonmentStatus = "ACTIVE",
                policyId = null,
                policyVersion = null,
                lastCartMutationAt = now,
                lastCriticalEventAt = now,
                lastPurchaseAt = lastPurchaseAt,
                stateVersion = stateVersion,
                cartSnapshotJson = """{"items":["sku-1"]}""",
                stitchedIdentityJson = """{"stitched":true}""",
            )
        }

        private fun sampleAttempt(
            attemptId: String,
            cartId: String,
            scheduledAt: OffsetDateTime,
            templateKey: String = "push-default",
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
                templateKey = templateKey,
                status = "SCHEDULED",
                suppressionReason = null,
                sendIdempotencyKey = "send-$attemptId",
                frequencyCapResult = null,
                providerResultJson = null,
            )
        }
    }
}
