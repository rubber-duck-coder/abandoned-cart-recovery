package com.abandonedcart.recovery.db

import com.abandonedcart.recovery.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object DataSourceFactory {
    fun create(config: AppConfig): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.postgresJdbcUrl
            username = config.postgresUser
            password = config.postgresPassword
            maximumPoolSize = config.postgresPoolSize
            minimumIdle = 1
            isAutoCommit = true
        }
        return HikariDataSource(hikariConfig)
    }
}

