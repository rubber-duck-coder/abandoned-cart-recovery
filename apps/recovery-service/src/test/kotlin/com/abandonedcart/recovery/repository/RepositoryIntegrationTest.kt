package com.abandonedcart.recovery.repository

import com.abandonedcart.recovery.db.FlywayMigrator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

class RepositoryIntegrationTest {
    @Test
    fun `cart recovery state upsert inserts and fetches rows`() {
        val repository = CartRecoveryStateRepository(dataSource)
        val state = sampleCartState(cartStatus = "ACTIVE", stateVersion = 1)

        val result = repository.upsert(state)
        val loaded = repository.findByCartId(state.cartId)

        assertEquals(CartRecoveryStateWriteResult.APPLIED, result)
        assertNotNull(loaded)
        assertEquals("ACTIVE", loaded.cartStatus)
        assertTrue(loaded.cartSnapshotJson.contains("sku-1"))
    }

    @Test
    fun `cart recovery state ignores stale versions and terminal cart rewrites`() {
        val repository = CartRecoveryStateRepository(dataSource)
        val purchased = sampleCartState(
            cartStatus = "PURCHASED",
            stateVersion = 3,
            lastPurchaseAt = OffsetDateTime.now(),
        )
        val stale = sampleCartState(cartStatus = "ACTIVE", stateVersion = 2)
        val futureMutation = sampleCartState(cartStatus = "ACTIVE", stateVersion = 4)

        repository.upsert(purchased)
        val staleResult = repository.upsert(stale)
        val terminalResult = repository.upsert(futureMutation)

        assertEquals(CartRecoveryStateWriteResult.NO_OP_TERMINAL_STATE, staleResult)
        assertEquals(CartRecoveryStateWriteResult.NO_OP_TERMINAL_STATE, terminalResult)
        assertEquals("PURCHASED", repository.findByCartId(purchased.cartId)?.cartStatus)
    }

    @Test
    fun `identity link updates terminal cart identity without reopening eligibility`() {
        val repository = CartRecoveryStateRepository(dataSource)
        val purchased = sampleCartState(
            cartStatus = "PURCHASED",
            stateVersion = 3,
            userId = null,
            anonymousId = "anon-terminal",
            stitchedIdentityJson = null,
            lastPurchaseAt = OffsetDateTime.now(),
        )
        val identityLinked = purchased.copy(
            userId = "user-terminal",
            stitchedIdentityJson = """{"linked":true}""",
            stateVersion = 4,
        )

        repository.upsert(purchased)
        val result = repository.upsertIdentityLink(identityLinked)
        val loaded = repository.findByCartId(purchased.cartId)

        assertEquals(CartRecoveryStateWriteResult.APPLIED, result)
        assertNotNull(loaded)
        assertEquals("PURCHASED", loaded.cartStatus)
        assertEquals("user-terminal", loaded.userId)
        assertTrue(loaded.stitchedIdentityJson?.contains("linked") == true)
    }

    @Test
    fun `recovery attempt scheduling is idempotent and status updates persist`() {
        val repository = RecoveryAttemptRepository(dataSource)
        val scheduledAt = OffsetDateTime.now().plusHours(24)
        val attempt = sampleAttempt(scheduledAt = scheduledAt)

        val created = repository.schedule(attempt)
        val duplicate = repository.schedule(attempt.copy(attemptId = "attempt-2"))
        val updated = repository.updateExecutionOutcome(
            attemptId = attempt.attemptId,
            status = "SENT",
            executedAt = OffsetDateTime.now(),
            suppressionReason = null,
            frequencyCapResult = "ALLOWED",
            providerResultJson = """{"provider":"mock"}""",
        )
        val attempts = repository.findByCartId(attempt.cartId)

        assertTrue(created)
        assertEquals(false, duplicate)
        assertTrue(updated)
        assertEquals(1, attempts.size)
        assertEquals("SENT", attempts.first().status)
        assertTrue(attempts.first().providerResultJson?.contains("mock") == true)
    }

    @Test
    fun `claim due attempts marks rows dispatching and avoids double claim`() {
        val repository = RecoveryAttemptRepository(dataSource)
        val dueAttempt = sampleAttempt(scheduledAt = OffsetDateTime.now().minusMinutes(1))

        assertTrue(repository.schedule(dueAttempt))

        val claimed = repository.claimDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))
        val claimedAgain = repository.claimDueAttempts(limit = 10, leaseDuration = Duration.ofMinutes(5))
        val attempts = repository.findByCartId(dueAttempt.cartId)

        assertEquals(1, claimed.size)
        assertEquals(0, claimedAgain.size)
        assertEquals("DISPATCHING", attempts.first().status)
        assertNotNull(attempts.first().leaseUntil)
    }

    @Test
    fun `rebind scheduled attempts updates only scheduled rows`() {
        val repository = RecoveryAttemptRepository(dataSource)
        val scheduledAttempt = sampleAttempt(
            scheduledAt = OffsetDateTime.now().plusHours(24),
            attemptId = "attempt-scheduled",
            userId = null,
            status = "SCHEDULED",
        )
        val sentAttempt = sampleAttempt(
            scheduledAt = OffsetDateTime.now().plusHours(72),
            attemptId = "attempt-sent",
            userId = null,
            status = "SENT",
            executedAt = OffsetDateTime.now(),
        )

        assertTrue(repository.schedule(scheduledAttempt))
        assertTrue(repository.schedule(sentAttempt))

        val updatedCount = repository.rebindScheduledAttempts("cart-1", "user-rebound")
        val attempts = repository.findByCartId("cart-1")
        val rebound = attempts.first { it.attemptId == "attempt-scheduled" }
        val sent = attempts.first { it.attemptId == "attempt-sent" }

        assertEquals(1, updatedCount)
        assertEquals("user-rebound", rebound.userId)
        assertEquals(null, sent.userId)
        assertEquals("SENT", sent.status)
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
        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = System.getenv().getOrDefault(
                        "TEST_POSTGRES_JDBC_URL",
                        "jdbc:postgresql://localhost:5432/recovery",
                    )
                    username = System.getenv().getOrDefault("TEST_POSTGRES_USER", "recovery")
                    password = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "recovery")
                    maximumPoolSize = 2
                },
            )
            FlywayMigrator(dataSource).migrate()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            dataSource.close()
        }

        private fun sampleCartState(
            cartId: String = "cart-1",
            cartStatus: String,
            stateVersion: Long,
            anonymousId: String = "anon-1",
            userId: String? = "user-1",
            stitchedIdentityJson: String? = """{"stitched":true}""",
            lastPurchaseAt: OffsetDateTime? = null,
        ): CartRecoveryState {
            val now = OffsetDateTime.now()
            return CartRecoveryState(
                cartId = cartId,
                tenantId = "tenant-1",
                anonymousId = anonymousId,
                userId = userId,
                cartStatus = cartStatus,
                abandonmentStatus = "ACTIVE",
                policyId = null,
                policyVersion = null,
                lastCartMutationAt = now,
                lastCriticalEventAt = now,
                lastPurchaseAt = lastPurchaseAt,
                stateVersion = stateVersion,
                cartSnapshotJson = """{"items":["sku-1"]}""",
                stitchedIdentityJson = stitchedIdentityJson,
            )
        }

        private fun sampleAttempt(
            scheduledAt: OffsetDateTime,
            attemptId: String = "attempt-1",
            userId: String? = "user-1",
            status: String = "SCHEDULED",
            executedAt: OffsetDateTime? = null,
        ): RecoveryAttempt {
            return RecoveryAttempt(
                attemptId = attemptId,
                cartId = "cart-1",
                tenantId = "tenant-1",
                userId = userId,
                experimentId = "exp-1",
                experimentName = "default-recovery",
                variantId = "control",
                policyId = "default",
                policyVersion = 1,
                touchIndex = 1,
                scheduledAt = scheduledAt,
                executedAt = executedAt,
                channel = "push",
                templateKey = "push-default",
                status = status,
                suppressionReason = null,
                sendIdempotencyKey = "send-$attemptId",
                frequencyCapResult = null,
                providerResultJson = null,
            )
        }
    }
}
