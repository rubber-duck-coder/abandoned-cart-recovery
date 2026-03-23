package com.abandonedcart.recovery

import com.abandonedcart.recovery.analytics.AnalyticsPublisher
import com.abandonedcart.recovery.analytics.KafkaAnalyticsPublisher
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.db.DataSourceFactory
import com.abandonedcart.recovery.db.FlywayMigrator
import com.abandonedcart.recovery.dispatcher.DueAttemptDispatcher
import com.abandonedcart.recovery.eligibility.EligibilityEvaluator
import com.abandonedcart.recovery.executor.DueAttemptExecutor
import com.abandonedcart.recovery.experiment.ExperimentClient
import com.abandonedcart.recovery.experiment.MockExperimentClient
import com.abandonedcart.recovery.frequencycap.FrequencyCapClient
import com.abandonedcart.recovery.frequencycap.MockFrequencyCapClient
import com.abandonedcart.recovery.kafka.KafkaJsonProducer
import com.abandonedcart.recovery.kafka.KafkaLoggingConsumer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.notification.MockNotificationSender
import com.abandonedcart.recovery.notification.NotificationSender
import com.abandonedcart.recovery.policy.RecoveryPolicyService
import com.abandonedcart.recovery.processor.CartMutationProcessor
import com.abandonedcart.recovery.processor.CartStateEventProcessor
import com.abandonedcart.recovery.repository.CartRecoveryStateRepository
import com.abandonedcart.recovery.repository.RecoveryAttemptRepository
import com.abandonedcart.recovery.scheduler.RecoveryScheduler
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

    @Provides
    @Singleton
    fun provideJsonCodec(): JsonCodec = JsonCodec()

    @Provides
    @Singleton
    fun provideCartRecoveryStateRepository(dataSource: DataSource): CartRecoveryStateRepository =
        CartRecoveryStateRepository(dataSource)

    @Provides
    @Singleton
    fun provideRecoveryAttemptRepository(dataSource: DataSource): RecoveryAttemptRepository =
        RecoveryAttemptRepository(dataSource)

    @Provides
    @Singleton
    fun provideCartMutationProcessor(repository: CartRecoveryStateRepository): CartMutationProcessor =
        CartMutationProcessor(repository)

    @Provides
    @Singleton
    fun provideCartStateEventProcessor(repository: CartRecoveryStateRepository): CartStateEventProcessor =
        CartStateEventProcessor(repository)

    @Provides
    @Singleton
    fun provideExperimentClient(): ExperimentClient = MockExperimentClient()

    @Provides
    @Singleton
    fun provideRecoveryPolicyService(experimentClient: ExperimentClient): RecoveryPolicyService =
        RecoveryPolicyService(experimentClient)

    @Provides
    @Singleton
    fun provideAnalyticsPublisher(
        appConfig: AppConfig,
        kafkaJsonProducer: KafkaJsonProducer,
    ): AnalyticsPublisher = KafkaAnalyticsPublisher(appConfig, kafkaJsonProducer)

    @Provides
    @Singleton
    fun provideRecoveryScheduler(
        cartRecoveryStateRepository: CartRecoveryStateRepository,
        recoveryAttemptRepository: RecoveryAttemptRepository,
        recoveryPolicyService: RecoveryPolicyService,
        analyticsPublisher: AnalyticsPublisher,
    ): RecoveryScheduler = RecoveryScheduler(
        cartRecoveryStateRepository,
        recoveryAttemptRepository,
        recoveryPolicyService,
        analyticsPublisher,
    )

    @Provides
    @Singleton
    fun provideDueAttemptDispatcher(
        appConfig: AppConfig,
        recoveryAttemptRepository: RecoveryAttemptRepository,
        kafkaJsonProducer: KafkaJsonProducer,
    ): DueAttemptDispatcher = DueAttemptDispatcher(
        recoveryAttemptRepository,
        kafkaJsonProducer,
        appConfig.recoveryAttemptsTopic,
    )

    @Provides
    @Singleton
    fun provideEligibilityEvaluator(): EligibilityEvaluator = EligibilityEvaluator()

    @Provides
    @Singleton
    fun provideFrequencyCapClient(): FrequencyCapClient = MockFrequencyCapClient()

    @Provides
    @Singleton
    fun provideNotificationSender(): NotificationSender = MockNotificationSender()

    @Provides
    @Singleton
    fun provideDueAttemptExecutor(
        recoveryAttemptRepository: RecoveryAttemptRepository,
        cartRecoveryStateRepository: CartRecoveryStateRepository,
        eligibilityEvaluator: EligibilityEvaluator,
        frequencyCapClient: FrequencyCapClient,
        notificationSender: NotificationSender,
        analyticsPublisher: AnalyticsPublisher,
    ): DueAttemptExecutor = DueAttemptExecutor(
        recoveryAttemptRepository,
        cartRecoveryStateRepository,
        eligibilityEvaluator,
        frequencyCapClient,
        notificationSender,
        analyticsPublisher,
    )

    @Provides
    @Singleton
    fun provideKafkaTopicBootstrapper(appConfig: AppConfig): KafkaTopicBootstrapper = KafkaTopicBootstrapper(appConfig)

    @Provides
    @Singleton
    fun provideKafkaLoggingConsumer(
        appConfig: AppConfig,
        jsonCodec: JsonCodec,
        kafkaJsonProducer: KafkaJsonProducer,
        cartMutationProcessor: CartMutationProcessor,
        cartStateEventProcessor: CartStateEventProcessor,
        recoveryScheduler: RecoveryScheduler,
        dueAttemptExecutor: DueAttemptExecutor,
    ): KafkaLoggingConsumer = KafkaLoggingConsumer(
        appConfig,
        jsonCodec,
        kafkaJsonProducer,
        cartMutationProcessor,
        cartStateEventProcessor,
        recoveryScheduler,
        dueAttemptExecutor,
    )

    @Provides
    @Singleton
    fun provideKafkaJsonProducer(appConfig: AppConfig, jsonCodec: JsonCodec): KafkaJsonProducer =
        KafkaJsonProducer(appConfig, jsonCodec)
}
