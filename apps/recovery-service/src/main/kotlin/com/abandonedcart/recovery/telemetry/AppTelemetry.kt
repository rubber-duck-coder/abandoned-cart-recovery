package com.abandonedcart.recovery.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider

class AppTelemetry private constructor(
    val openTelemetry: OpenTelemetry,
    private val metricReader: PrometheusHttpServer,
    private val meterProvider: SdkMeterProvider,
) : AutoCloseable {
    override fun close() {
        metricReader.close()
        meterProvider.close()
        (openTelemetry as? OpenTelemetrySdk)?.close()
    }

    companion object {
        fun create(host: String, port: Int): AppTelemetry {
            val metricReader = PrometheusHttpServer.builder()
                .setHost(host)
                .setPort(port)
                .build()
            val meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build()
            return AppTelemetry(openTelemetry, metricReader, meterProvider)
        }
    }
}
