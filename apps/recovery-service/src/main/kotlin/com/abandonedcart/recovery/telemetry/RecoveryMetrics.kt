package com.abandonedcart.recovery.telemetry

interface RecoveryMetrics {
    fun recordServiceStart()

    fun recordKafkaConsumed(topic: String)

    fun recordKafkaProcessed(topic: String, outcome: String, durationMs: Double)

    fun recordKafkaRouted(sourceTopic: String, targetTopic: String, outcome: String)

    fun recordKafkaPublished(topic: String, result: String, durationMs: Double)

    fun recordStateWrite(source: String, result: String)

    fun recordRepositoryOperation(repository: String, operation: String, outcome: String, durationMs: Double)

    fun recordSchedulerOutcome(outcome: String)

    fun recordAttemptScheduled(channel: String, result: String)

    fun recordDispatchBatch(batchSize: Long)

    fun recordAttemptDispatched(channel: String, result: String)

    fun recordAttemptExecuted(channel: String, outcome: String)

    fun recordAnalyticsPublished(eventType: String, result: String)

    fun recordStageDuration(stage: String, outcome: String, durationMs: Double)
}

object NoOpRecoveryMetrics : RecoveryMetrics {
    override fun recordServiceStart() = Unit

    override fun recordKafkaConsumed(topic: String) = Unit

    override fun recordKafkaProcessed(topic: String, outcome: String, durationMs: Double) = Unit

    override fun recordKafkaRouted(sourceTopic: String, targetTopic: String, outcome: String) = Unit

    override fun recordKafkaPublished(topic: String, result: String, durationMs: Double) = Unit

    override fun recordStateWrite(source: String, result: String) = Unit

    override fun recordRepositoryOperation(repository: String, operation: String, outcome: String, durationMs: Double) = Unit

    override fun recordSchedulerOutcome(outcome: String) = Unit

    override fun recordAttemptScheduled(channel: String, result: String) = Unit

    override fun recordDispatchBatch(batchSize: Long) = Unit

    override fun recordAttemptDispatched(channel: String, result: String) = Unit

    override fun recordAttemptExecuted(channel: String, outcome: String) = Unit

    override fun recordAnalyticsPublished(eventType: String, result: String) = Unit

    override fun recordStageDuration(stage: String, outcome: String, durationMs: Double) = Unit
}
