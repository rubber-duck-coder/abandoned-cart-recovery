package com.abandonedcart.recovery.telemetry

import com.zaxxer.hikari.HikariDataSource
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class DatabaseTelemetry(
    openTelemetry: OpenTelemetry,
    private val dataSource: DataSource,
) {
    private val meter = openTelemetry.getMeter("recovery-service")
    private val trackedTables = listOf("cart_recovery_state", "recovery_attempt")

    init {
        meter.gaugeBuilder("recovery_db_table_size_bytes")
            .setDescription("Postgres table size in bytes for tracked recovery tables")
            .setUnit("By")
            .buildWithCallback { measurement ->
                fetchTableStats().forEach { stats ->
                    measurement.record(
                        stats.sizeBytes.toDouble(),
                        Attributes.of(stringKey("table"), stats.tableName),
                    )
                }
            }
        meter.gaugeBuilder("recovery_db_estimated_rows")
            .setDescription("Estimated row count for tracked recovery tables")
            .buildWithCallback { measurement ->
                fetchTableStats().forEach { stats ->
                    measurement.record(
                        stats.estimatedRows.toDouble(),
                        Attributes.of(stringKey("table"), stats.tableName),
                    )
                }
            }

        val hikariDataSource = dataSource as? HikariDataSource
        if (hikariDataSource != null) {
            meter.gaugeBuilder("recovery_db_pool_active_connections")
                .setDescription("Active Hikari connections")
                .buildWithCallback { measurement ->
                    measurement.record(hikariDataSource.hikariPoolMXBean.activeConnections.toDouble())
                }
            meter.gaugeBuilder("recovery_db_pool_idle_connections")
                .setDescription("Idle Hikari connections")
                .buildWithCallback { measurement ->
                    measurement.record(hikariDataSource.hikariPoolMXBean.idleConnections.toDouble())
                }
            meter.gaugeBuilder("recovery_db_pool_total_connections")
                .setDescription("Total Hikari connections")
                .buildWithCallback { measurement ->
                    measurement.record(hikariDataSource.hikariPoolMXBean.totalConnections.toDouble())
                }
            meter.gaugeBuilder("recovery_db_pool_pending_threads")
                .setDescription("Threads waiting for a Hikari connection")
                .buildWithCallback { measurement ->
                    measurement.record(hikariDataSource.hikariPoolMXBean.threadsAwaitingConnection.toDouble())
                }
        }
    }

    private fun fetchTableStats(): List<TableStats> {
        return dataSource.connection.use { connection: Connection ->
            connection.prepareStatement(
                """
                select
                  relname as table_name,
                  pg_total_relation_size((quote_ident(schemaname) || '.' || quote_ident(relname))::regclass) as table_size_bytes,
                  coalesce(n_live_tup, 0) as estimated_rows
                from pg_stat_user_tables
                where schemaname = 'public'
                  and relname = any (?)
                order by relname
                """.trimIndent(),
            ).use { statement ->
                val tableArray = statement.connection.createArrayOf("text", trackedTables.toTypedArray())
                try {
                    statement.setArray(1, tableArray)
                    statement.executeQuery().use { resultSet ->
                        val rows = mutableListOf<TableStats>()
                        while (resultSet.next()) {
                            rows += resultSet.toTableStats()
                        }
                        rows
                    }
                } finally {
                    tableArray.free()
                }
            }
        }
    }

    private fun ResultSet.toTableStats(): TableStats {
        return TableStats(
            tableName = getString("table_name"),
            sizeBytes = getLong("table_size_bytes"),
            estimatedRows = getLong("estimated_rows"),
        )
    }

    private fun stringKey(name: String): AttributeKey<String> = AttributeKey.stringKey(name)

    private data class TableStats(
        val tableName: String,
        val sizeBytes: Long,
        val estimatedRows: Long,
    )
}
