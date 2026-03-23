package com.abandonedcart.recovery.db

import javax.sql.DataSource
import org.flywaydb.core.Flyway

class FlywayMigrator(
    private val dataSource: DataSource,
) {
    fun migrate(): Int {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return result.migrationsExecuted
    }
}

