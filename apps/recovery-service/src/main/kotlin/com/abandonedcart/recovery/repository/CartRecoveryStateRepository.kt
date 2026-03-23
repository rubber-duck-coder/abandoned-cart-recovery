package com.abandonedcart.recovery.repository

import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

class CartRecoveryStateRepository(
    private val dataSource: DataSource,
) {
    fun findByCartId(cartId: String): CartRecoveryState? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select cart_id, tenant_id, anonymous_id, user_id, cart_status, abandonment_status,
                       policy_id, policy_version, last_cart_mutation_at, last_critical_event_at,
                       last_purchase_at, state_version, cart_snapshot_json, stitched_identity_json,
                       created_at, updated_at
                from cart_recovery_state
                where cart_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, cartId)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        resultSet.toCartRecoveryState()
                    } else {
                        null
                    }
                }
            }
        }
    }

    fun upsert(state: CartRecoveryState): CartRecoveryStateWriteResult {
        val now = OffsetDateTime.now()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val updated = connection.prepareStatement(
                """
                insert into cart_recovery_state (
                  cart_id, tenant_id, anonymous_id, user_id, cart_status, abandonment_status,
                  policy_id, policy_version, last_cart_mutation_at, last_critical_event_at,
                  last_purchase_at, state_version, cart_snapshot_json, stitched_identity_json,
                  created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?)
                on conflict (cart_id) do update
                  set tenant_id = excluded.tenant_id,
                      anonymous_id = excluded.anonymous_id,
                      user_id = excluded.user_id,
                      cart_status = excluded.cart_status,
                      abandonment_status = excluded.abandonment_status,
                      policy_id = excluded.policy_id,
                      policy_version = excluded.policy_version,
                      last_cart_mutation_at = excluded.last_cart_mutation_at,
                      last_critical_event_at = excluded.last_critical_event_at,
                      last_purchase_at = excluded.last_purchase_at,
                      state_version = excluded.state_version,
                      cart_snapshot_json = excluded.cart_snapshot_json,
                      stitched_identity_json = excluded.stitched_identity_json,
                      updated_at = excluded.updated_at
                where cart_recovery_state.state_version < excluded.state_version
                  and cart_recovery_state.cart_status not in ('PURCHASED', 'DELETED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, state.cartId)
                statement.setString(2, state.tenantId)
                statement.setNullableText(3, state.anonymousId)
                statement.setNullableText(4, state.userId)
                statement.setString(5, state.cartStatus)
                statement.setString(6, state.abandonmentStatus)
                statement.setNullableText(7, state.policyId)
                statement.setNullableInt(8, state.policyVersion)
                statement.setNullableOffsetDateTime(9, state.lastCartMutationAt)
                statement.setNullableOffsetDateTime(10, state.lastCriticalEventAt)
                statement.setNullableOffsetDateTime(11, state.lastPurchaseAt)
                statement.setLong(12, state.stateVersion)
                statement.setString(13, state.cartSnapshotJson)
                statement.setString(14, state.stitchedIdentityJson ?: "null")
                statement.setOffsetDateTime(15, now)
                statement.setOffsetDateTime(16, now)
                statement.executeUpdate()
            }
            if (updated > 0) {
                connection.commit()
                return CartRecoveryStateWriteResult.APPLIED
            }
            val existing = connection.findCartState(state.cartId)
            connection.commit()
            return when {
                existing == null -> CartRecoveryStateWriteResult.NO_OP_STALE_VERSION
                existing.cartStatus in TERMINAL_CART_STATUSES -> CartRecoveryStateWriteResult.NO_OP_TERMINAL_STATE
                else -> CartRecoveryStateWriteResult.NO_OP_STALE_VERSION
            }
        }
    }

    private fun Connection.findCartState(cartId: String): CartRecoveryState? {
        prepareStatement(
            """
            select cart_id, tenant_id, anonymous_id, user_id, cart_status, abandonment_status,
                   policy_id, policy_version, last_cart_mutation_at, last_critical_event_at,
                   last_purchase_at, state_version, cart_snapshot_json, stitched_identity_json,
                   created_at, updated_at
            from cart_recovery_state
            where cart_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, cartId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) resultSet.toCartRecoveryState() else null
            }
        }
    }

    private fun ResultSet.toCartRecoveryState(): CartRecoveryState {
        return CartRecoveryState(
            cartId = getString("cart_id"),
            tenantId = getString("tenant_id"),
            anonymousId = getString("anonymous_id"),
            userId = getString("user_id"),
            cartStatus = getString("cart_status"),
            abandonmentStatus = getString("abandonment_status"),
            policyId = getString("policy_id"),
            policyVersion = getNullableInt("policy_version"),
            lastCartMutationAt = getNullableOffsetDateTime("last_cart_mutation_at"),
            lastCriticalEventAt = getNullableOffsetDateTime("last_critical_event_at"),
            lastPurchaseAt = getNullableOffsetDateTime("last_purchase_at"),
            stateVersion = getLong("state_version"),
            cartSnapshotJson = getString("cart_snapshot_json"),
            stitchedIdentityJson = getString("stitched_identity_json"),
            createdAt = getNullableOffsetDateTime("created_at"),
            updatedAt = getNullableOffsetDateTime("updated_at"),
        )
    }

    companion object {
        private val TERMINAL_CART_STATUSES = setOf("PURCHASED", "DELETED")
    }
}

enum class CartRecoveryStateWriteResult {
    APPLIED,
    NO_OP_STALE_VERSION,
    NO_OP_TERMINAL_STATE,
}
