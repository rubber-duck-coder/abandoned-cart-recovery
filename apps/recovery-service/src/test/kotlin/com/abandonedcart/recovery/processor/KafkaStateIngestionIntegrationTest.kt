package com.abandonedcart.recovery.processor

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.CartMutationEvent
import com.abandonedcart.recovery.contract.CartStateEvent
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.db.FlywayMigrator
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaLoggingConsumer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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

    @BeforeEach
    fun truncateTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("truncate table recovery_attempt, cart_recovery_state")
            }
        }
    }

    private fun waitForState(cartId: String, expectedStatus: String? = null): com.abandonedcart.recovery.repository.CartRecoveryState? {
        repeat(20) {
            val state = repository.findByCartId(cartId)
            if (state != null && (expectedStatus == null || state.cartStatus == expectedStatus)) {
                return state
            }
            Thread.sleep(250)
        }
        return repository.findByCartId(cartId)
    }

    companion object {
        private val appConfig = AppConfig.fromEnv(
            mapOf(
                "KAFKA_BOOTSTRAP_SERVERS" to System.getenv().getOrDefault("TEST_KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
                "KAFKA_CONSUMER_GROUP_ID" to "processor-test-${UUID.randomUUID()}",
                "POSTGRES_JDBC_URL" to System.getenv().getOrDefault("TEST_POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/recovery"),
                "POSTGRES_USER" to System.getenv().getOrDefault("TEST_POSTGRES_USER", "recovery"),
                "POSTGRES_PASSWORD" to System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "recovery"),
            ),
        )
        private val jsonCodec = JsonCodec()
        private lateinit var dataSource: HikariDataSource
        private lateinit var repository: CartRecoveryStateRepository
        private lateinit var kafkaProducer: KafkaJsonProducer
        private lateinit var kafkaConsumer: KafkaLoggingConsumer

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
            kafkaProducer = KafkaJsonProducer(appConfig, jsonCodec)
            val mutationProcessor = CartMutationProcessor(repository)
            val stateEventProcessor = CartStateEventProcessor(repository)
            KafkaTopicBootstrapper(appConfig).ensureTopics()
            kafkaConsumer = KafkaLoggingConsumer(appConfig, jsonCodec, kafkaProducer, mutationProcessor, stateEventProcessor)
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
