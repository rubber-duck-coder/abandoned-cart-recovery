package com.abandonedcart.recovery.telemetry

import com.sun.management.OperatingSystemMXBean
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean

class OpenTelemetryRecoveryMetrics(
    openTelemetry: OpenTelemetry,
) : RecoveryMetrics {
    private val meter: Meter = openTelemetry.getMeter("recovery-service")
    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val operatingSystemBean: OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean

    private val startupCounter: LongCounter = counter(
        "recovery_service_startups_total",
        "Number of recovery service starts",
    )
    private val kafkaConsumedCounter: LongCounter = counter(
        "recovery_kafka_events_consumed_total",
        "Kafka records consumed by topic",
    )
    private val kafkaProcessedCounter: LongCounter = counter(
        "recovery_kafka_events_processed_total",
        "Kafka records processed by topic and outcome",
    )
    private val kafkaRouteCounter: LongCounter = counter(
        "recovery_kafka_event_routing_total",
        "Commerce event routing results",
    )
    private val kafkaPublishCounter: LongCounter = counter(
        "recovery_kafka_producer_publish_total",
        "Kafka publish attempts by topic and result",
    )
    private val stateWriteCounter: LongCounter = counter(
        "recovery_state_writes_total",
        "State writes by source and result",
    )
    private val repositoryOperationCounter: LongCounter = counter(
        "recovery_repository_operations_total",
        "Repository operations by name and result",
    )
    private val schedulerOutcomeCounter: LongCounter = counter(
        "recovery_scheduler_outcomes_total",
        "Scheduler decisions and skips",
    )
    private val attemptsScheduledCounter: LongCounter = counter(
        "recovery_attempts_scheduled_total",
        "Recovery attempts scheduled by channel and result",
    )
    private val dispatchCounter: LongCounter = counter(
        "recovery_attempts_dispatched_total",
        "Recovery attempts dispatched by channel and result",
    )
    private val attemptsExecutedCounter: LongCounter = counter(
        "recovery_attempts_executed_total",
        "Recovery attempts executed by channel and outcome",
    )
    private val analyticsPublishedCounter: LongCounter = counter(
        "recovery_analytics_events_published_total",
        "Analytics events published by type and result",
    )
    private val dispatchBatchHistogram: DoubleHistogram = histogram(
        "recovery_dispatch_claim_batch_size",
        "Batch size of due attempts claimed by the dispatcher",
        "",
    )
    private val kafkaProcessingDurationHistogram: DoubleHistogram = histogram(
        "recovery_kafka_processing_duration_ms",
        "Kafka record processing duration in milliseconds",
        "ms",
    )
    private val kafkaPublishDurationHistogram: DoubleHistogram = histogram(
        "recovery_kafka_publish_duration_ms",
        "Kafka publish duration in milliseconds",
        "ms",
    )
    private val repositoryDurationHistogram: DoubleHistogram = histogram(
        "recovery_repository_operation_duration_ms",
        "Repository operation duration in milliseconds",
        "ms",
    )
    private val stageDurationHistogram: DoubleHistogram = histogram(
        "recovery_stage_duration_ms",
        "Application stage duration in milliseconds",
        "ms",
    )

    init {
        meter.gaugeBuilder("recovery_runtime_heap_used_bytes")
            .setDescription("Heap memory used by the recovery service")
            .setUnit("By")
            .buildWithCallback { measurement ->
                measurement.record(memoryBean.heapMemoryUsage.used.toDouble())
            }
        meter.gaugeBuilder("recovery_runtime_non_heap_used_bytes")
            .setDescription("Non-heap memory used by the recovery service")
            .setUnit("By")
            .buildWithCallback { measurement ->
                measurement.record(memoryBean.nonHeapMemoryUsage.used.toDouble())
            }
        meter.gaugeBuilder("recovery_runtime_thread_count")
            .setDescription("Current live thread count")
            .buildWithCallback { measurement ->
                measurement.record(ManagementFactory.getThreadMXBean().threadCount.toDouble())
            }
        meter.gaugeBuilder("recovery_runtime_process_cpu_load_ratio")
            .setDescription("Process CPU load ratio when available")
            .setUnit("1")
            .buildWithCallback { measurement ->
                val cpuLoad = operatingSystemBean?.processCpuLoad ?: -1.0
                if (cpuLoad >= 0.0) {
                    measurement.record(cpuLoad)
                }
            }
    }

    override fun recordServiceStart() {
        startupCounter.add(1)
    }

    override fun recordKafkaConsumed(topic: String) {
        kafkaConsumedCounter.add(
            1,
            Attributes.of(stringKey("topic"), topic),
        )
    }

    override fun recordKafkaProcessed(topic: String, outcome: String, durationMs: Double) {
        val attributes = Attributes.of(
            stringKey("topic"),
            topic,
            stringKey("outcome"),
            outcome,
        )
        kafkaProcessedCounter.add(1, attributes)
        kafkaProcessingDurationHistogram.record(durationMs, attributes)
    }

    override fun recordKafkaRouted(sourceTopic: String, targetTopic: String, outcome: String) {
        kafkaRouteCounter.add(
            1,
            Attributes.of(
                stringKey("source_topic"),
                sourceTopic,
                stringKey("target_topic"),
                targetTopic,
                stringKey("outcome"),
                outcome,
            ),
        )
    }

    override fun recordKafkaPublished(topic: String, result: String, durationMs: Double) {
        val attributes = Attributes.of(
            stringKey("topic"),
            topic,
            stringKey("result"),
            result,
        )
        kafkaPublishCounter.add(1, attributes)
        kafkaPublishDurationHistogram.record(durationMs, attributes)
    }

    override fun recordStateWrite(source: String, result: String) {
        stateWriteCounter.add(
            1,
            Attributes.of(
                stringKey("source"),
                source,
                stringKey("result"),
                result,
            ),
        )
    }

    override fun recordRepositoryOperation(repository: String, operation: String, outcome: String, durationMs: Double) {
        val attributes = Attributes.of(
            stringKey("repository"),
            repository,
            stringKey("operation"),
            operation,
            stringKey("outcome"),
            outcome,
        )
        repositoryOperationCounter.add(1, attributes)
        repositoryDurationHistogram.record(durationMs, attributes)
    }

    override fun recordSchedulerOutcome(outcome: String) {
        schedulerOutcomeCounter.add(
            1,
            Attributes.of(stringKey("outcome"), outcome),
        )
    }

    override fun recordAttemptScheduled(channel: String, result: String) {
        attemptsScheduledCounter.add(
            1,
            Attributes.of(
                stringKey("channel"),
                channel,
                stringKey("result"),
                result,
            ),
        )
    }

    override fun recordDispatchBatch(batchSize: Long) {
        dispatchBatchHistogram.record(batchSize.toDouble())
    }

    override fun recordAttemptDispatched(channel: String, result: String) {
        dispatchCounter.add(
            1,
            Attributes.of(
                stringKey("channel"),
                channel,
                stringKey("result"),
                result,
            ),
        )
    }

    override fun recordAttemptExecuted(channel: String, outcome: String) {
        attemptsExecutedCounter.add(
            1,
            Attributes.of(
                stringKey("channel"),
                channel,
                stringKey("outcome"),
                outcome,
            ),
        )
    }

    override fun recordAnalyticsPublished(eventType: String, result: String) {
        analyticsPublishedCounter.add(
            1,
            Attributes.of(
                stringKey("event_type"),
                eventType,
                stringKey("result"),
                result,
            ),
        )
    }

    override fun recordStageDuration(stage: String, outcome: String, durationMs: Double) {
        stageDurationHistogram.record(
            durationMs,
            Attributes.of(
                stringKey("stage"),
                stage,
                stringKey("outcome"),
                outcome,
            ),
        )
    }

    private fun counter(name: String, description: String): LongCounter {
        return meter.counterBuilder(name)
            .setDescription(description)
            .build()
    }

    private fun histogram(name: String, description: String, unit: String): DoubleHistogram {
        return meter.histogramBuilder(name)
            .setDescription(description)
            .setUnit(unit)
            .build()
    }

    private fun stringKey(name: String): AttributeKey<String> = AttributeKey.stringKey(name)
}
