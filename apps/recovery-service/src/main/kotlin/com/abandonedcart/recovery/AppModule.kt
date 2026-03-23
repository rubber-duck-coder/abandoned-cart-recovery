package com.abandonedcart.recovery

import com.abandonedcart.recovery.db.DataSourceFactory
import com.abandonedcart.recovery.db.FlywayMigrator
import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import javax.sql.DataSource

class AppModule(
    private val config: AppConfig,
) : AbstractModule() {
    override fun configure() {
        bind(RecoveryApplication::class.java).`in`(Singleton::class.java)
    }

    @Provides
    @Singleton
    fun provideAppConfig(): AppConfig = config

    @Provides
    @Singleton
    fun provideDataSource(appConfig: AppConfig): DataSource = DataSourceFactory.create(appConfig)

    @Provides
    @Singleton
    fun provideFlywayMigrator(dataSource: DataSource): FlywayMigrator = FlywayMigrator(dataSource)
}
