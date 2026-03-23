package com.abandonedcart.recovery

import com.abandonedcart.recovery.db.FlywayMigrator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RecoveryApplication @Inject constructor(
    private val config: AppConfig,
    private val flywayMigrator: FlywayMigrator,
) {
    private val logger = LoggerFactory.getLogger(RecoveryApplication::class.java)
    private val shutdownLatch = CountDownLatch(1)

    fun start() {
        val migrationsApplied = flywayMigrator.migrate()
        val metricReader = PrometheusHttpServer.builder()
            .setHost(config.metricsHost)
            .setPort(config.metricsPort)
            .build()
        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metricReader)
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build()
        val startupCounter = startupCounter(openTelemetry)

        val server = HttpServer.create(InetSocketAddress(config.appHost, config.appPort), 0).apply {
            createContext("/", TextHandler("abandoned-cart-recovery"))
            createContext("/health", TextHandler("ok"))
            executor = Executors.newFixedThreadPool(4)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down recovery-service")
                server.stop(0)
                closeQuietly(metricReader)
                closeQuietly(meterProvider)
                shutdownLatch.countDown()
            },
        )

        server.start()
        startupCounter.add(1)
        logger.info(
            "recovery-service started http={}://{}:{} metrics={}://{}:{} kafka={} postgres={} migrationsApplied={}",
            "http",
            config.appHost,
            config.appPort,
            "http",
            config.metricsHost,
            config.metricsPort,
            config.kafkaBootstrapServers,
            config.postgresJdbcUrl,
            migrationsApplied,
        )

        shutdownLatch.await()
    }

    private fun startupCounter(openTelemetry: OpenTelemetry): LongCounter {
        return openTelemetry
            .getMeter("recovery-service")
            .counterBuilder("recovery_service_startups_total")
            .setDescription("Number of recovery service starts")
            .build()
    }

    private fun closeQuietly(closeable: Any) {
        if (closeable is Closeable) {
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
