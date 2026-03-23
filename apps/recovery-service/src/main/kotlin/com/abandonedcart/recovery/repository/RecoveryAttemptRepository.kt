package com.abandonedcart.recovery.repository

import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

class RecoveryAttemptRepository(
    private val dataSource: DataSource,
) {
    fun schedule(attempt: RecoveryAttempt): Boolean {
        val now = OffsetDateTime.now()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into recovery_attempt (
                  attempt_id, cart_id, tenant_id, user_id, experiment_id, experiment_name,
                  variant_id, policy_id, policy_version, touch_index, scheduled_at, executed_at,
                  channel, template_key, status, suppression_reason, send_idempotency_key,
                  frequency_cap_result, provider_result_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (cart_id, policy_version, touch_index, scheduled_at) do nothing
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, attempt.attemptId)
                statement.setString(2, attempt.cartId)
                statement.setString(3, attempt.tenantId)
                statement.setNullableText(4, attempt.userId)
                statement.setNullableText(5, attempt.experimentId)
                statement.setNullableText(6, attempt.experimentName)
                statement.setNullableText(7, attempt.variantId)
                statement.setString(8, attempt.policyId)
                statement.setInt(9, attempt.policyVersion)
                statement.setInt(10, attempt.touchIndex)
                statement.setObject(11, attempt.scheduledAt)
                statement.setNullableOffsetDateTime(12, attempt.executedAt)
                statement.setString(13, attempt.channel)
                statement.setString(14, attempt.templateKey)
                statement.setString(15, attempt.status)
                statement.setNullableText(16, attempt.suppressionReason)
                statement.setString(17, attempt.sendIdempotencyKey)
                statement.setNullableText(18, attempt.frequencyCapResult)
                statement.setString(19, attempt.providerResultJson ?: "null")
                statement.setObject(20, now)
                statement.setObject(21, now)
                return statement.executeUpdate() > 0
            }
        }
    }

    fun findByCartId(cartId: String): List<RecoveryAttempt> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select attempt_id, cart_id, tenant_id, user_id, experiment_id, experiment_name,
                       variant_id, policy_id, policy_version, touch_index, scheduled_at, executed_at,
                       channel, template_key, status, suppression_reason, send_idempotency_key,
                       frequency_cap_result, provider_result_json, created_at, updated_at
                from recovery_attempt
                where cart_id = ?
                order by scheduled_at asc
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, cartId)
                statement.executeQuery().use { resultSet ->
                    val attempts = mutableListOf<RecoveryAttempt>()
                    while (resultSet.next()) {
                        attempts += resultSet.toRecoveryAttempt()
                    }
                    return attempts
                }
            }
        }
    }

    fun updateExecutionOutcome(
        attemptId: String,
        status: String,
        executedAt: OffsetDateTime?,
        suppressionReason: String?,
        frequencyCapResult: String?,
        providerResultJson: String?,
    ): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update recovery_attempt
                set status = ?,
                    executed_at = ?,
                    suppression_reason = ?,
                    frequency_cap_result = ?,
                    provider_result_json = cast(? as jsonb),
                    updated_at = ?
                where attempt_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status)
                statement.setNullableOffsetDateTime(2, executedAt)
                statement.setNullableText(3, suppressionReason)
                statement.setNullableText(4, frequencyCapResult)
                statement.setString(5, providerResultJson ?: "null")
                statement.setObject(6, OffsetDateTime.now())
                statement.setString(7, attemptId)
                return statement.executeUpdate() > 0
            }
        }
    }

    private fun ResultSet.toRecoveryAttempt(): RecoveryAttempt {
        return RecoveryAttempt(
            attemptId = getString("attempt_id"),
            cartId = getString("cart_id"),
            tenantId = getString("tenant_id"),
            userId = getString("user_id"),
            experimentId = getString("experiment_id"),
            experimentName = getString("experiment_name"),
            variantId = getString("variant_id"),
            policyId = getString("policy_id"),
            policyVersion = getInt("policy_version"),
            touchIndex = getInt("touch_index"),
            scheduledAt = getObject("scheduled_at", OffsetDateTime::class.java),
            executedAt = getObject("executed_at", OffsetDateTime::class.java),
            channel = getString("channel"),
            templateKey = getString("template_key"),
            status = getString("status"),
            suppressionReason = getString("suppression_reason"),
            sendIdempotencyKey = getString("send_idempotency_key"),
            frequencyCapResult = getString("frequency_cap_result"),
            providerResultJson = getString("provider_result_json"),
            createdAt = getObject("created_at", OffsetDateTime::class.java),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java),
        )
    }
}

