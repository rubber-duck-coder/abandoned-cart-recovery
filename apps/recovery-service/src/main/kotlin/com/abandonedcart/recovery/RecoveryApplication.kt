package com.abandonedcart.recovery

import com.abandonedcart.recovery.kafka.KafkaLoggingConsumer
import com.abandonedcart.recovery.kafka.KafkaTopicBootstrapper
import com.abandonedcart.recovery.telemetry.AppTelemetry
import com.abandonedcart.recovery.telemetry.DatabaseTelemetry
import com.abandonedcart.recovery.telemetry.RecoveryMetrics
import com.abandonedcart.recovery.db.FlywayMigrator
import com.abandonedcart.recovery.dispatcher.DueAttemptDispatcher
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RecoveryApplication @Inject constructor(
    private val config: AppConfig,
    private val flywayMigrator: FlywayMigrator,
    private val kafkaTopicBootstrapper: KafkaTopicBootstrapper,
    private val kafkaLoggingConsumer: KafkaLoggingConsumer,
    private val dueAttemptDispatcher: DueAttemptDispatcher,
    private val appTelemetry: AppTelemetry,
    private val recoveryMetrics: RecoveryMetrics,
    @Suppress("unused")
    private val databaseTelemetry: DatabaseTelemetry,
) {
    private val logger = LoggerFactory.getLogger(RecoveryApplication::class.java)
    private val shutdownLatch = CountDownLatch(1)

    fun start() {
        val migrationsApplied = flywayMigrator.migrate()
        val kafkaTopicsCreated = kafkaTopicBootstrapper.ensureTopics()
        val dispatcherExecutor = startDispatcherLoop()

        val server = HttpServer.create(InetSocketAddress(config.appHost, config.appPort), 0).apply {
            createContext("/", TextHandler("abandoned-cart-recovery"))
            createContext("/health", TextHandler("ok"))
            executor = Executors.newFixedThreadPool(4)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down recovery-service")
                server.stop(0)
                kafkaLoggingConsumer.close()
                dispatcherExecutor?.shutdownNow()
                closeQuietly(appTelemetry)
                shutdownLatch.countDown()
            },
        )

        server.start()
        kafkaLoggingConsumer.start()
        recoveryMetrics.recordServiceStart()
        logger.info(
            "recovery-service started http={}://{}:{} metrics={}://{}:{} kafka={} postgres={} migrationsApplied={} kafkaTopicsCreated={}",
            "http",
            config.appHost,
            config.appPort,
            "http",
            config.metricsHost,
            config.metricsPort,
            config.kafkaBootstrapServers,
            config.postgresJdbcUrl,
            migrationsApplied,
            kafkaTopicsCreated,
        )

        shutdownLatch.await()
    }

    private fun startDispatcherLoop(): ScheduledExecutorService? {
        if (!config.dispatcherEnabled) {
            logger.info("Due-attempt dispatcher loop disabled")
            return null
        }
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay(
            {
                try {
                    dueAttemptDispatcher.dispatchDueAttempts(limit = config.dispatcherBatchSize)
                } catch (error: Exception) {
                    logger.error("Due-attempt dispatcher loop failed", error)
                }
            },
            0L,
            config.dispatcherPollIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        logger.info(
            "Due-attempt dispatcher loop started pollIntervalMs={} batchSize={}",
            config.dispatcherPollIntervalMs,
            config.dispatcherBatchSize,
        )
        return executor
    }

    private fun closeQuietly(closeable: Any) {
        if (closeable is AutoCloseable) {
            closeable.close()
        }
    }

    private class TextHandler(
        private val body: String,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { output ->
                output.write(payload)
            }
        }
    }
}
