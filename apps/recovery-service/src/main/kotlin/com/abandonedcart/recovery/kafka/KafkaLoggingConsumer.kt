package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import com.abandonedcart.recovery.contract.CartAbandonedEvent
import com.abandonedcart.recovery.contract.CartMutationEvent
import com.abandonedcart.recovery.contract.CartStateEvent
import com.abandonedcart.recovery.contract.JsonCodec
import com.abandonedcart.recovery.contract.RecoveryAttemptDueEvent
import com.abandonedcart.recovery.executor.DueAttemptExecutor
import com.abandonedcart.recovery.processor.CartMutationProcessor
import com.abandonedcart.recovery.processor.CartStateEventProcessor
import com.abandonedcart.recovery.scheduler.RecoveryScheduler
import com.abandonedcart.recovery.telemetry.NoOpRecoveryMetrics
import com.abandonedcart.recovery.telemetry.RecoveryMetrics
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

class KafkaLoggingConsumer(
    private val config: AppConfig,
    private val jsonCodec: JsonCodec,
    private val producer: KafkaJsonProducer,
    private val cartMutationProcessor: CartMutationProcessor,
    private val cartStateEventProcessor: CartStateEventProcessor,
    private val recoveryScheduler: RecoveryScheduler,
    private val dueAttemptExecutor: DueAttemptExecutor,
    private val recoveryMetrics: RecoveryMetrics = NoOpRecoveryMetrics,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(KafkaLoggingConsumer::class.java)
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val consumer = KafkaClientFactory.createStringConsumer(config)

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        consumer.subscribe(config.allKafkaTopics())
        executor.submit {
            logger.info("Kafka logging consumer subscribed topics={}", config.allKafkaTopics())
            try {
                while (running.get()) {
                    val records = consumer.poll(Duration.ofSeconds(1))
                    records.forEach { record ->
                        handleRecord(record)
                    }
                }
            } catch (_: WakeupException) {
                if (running.get()) {
                    throw WakeupException()
                }
            } finally {
                consumer.close()
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        consumer.wakeup()
        executor.shutdownNow()
        producer.close()
    }

    private fun handleRecord(record: ConsumerRecord<String, String>) {
        val startedAt = System.nanoTime()
        recoveryMetrics.recordKafkaConsumed(record.topic())
        var outcome = "success"
        logger.info(
            "Kafka message topic={} key={} value={}",
            record.topic(),
            record.key(),
            record.value(),
        )
        try {
            when (record.topic()) {
                config.commerceCartEventsTopic -> routeCommerceEvent(record)
                config.recoveryCartMutationsTopic -> {
                    val event = jsonCodec.fromJson<CartMutationEvent>(record.value())
                    cartMutationProcessor.process(event)
                }
                config.recoveryCartStateEventsTopic -> {
                    val event = jsonCodec.fromJson<CartStateEvent>(record.value())
                    cartStateEventProcessor.process(event)
                }
                config.recoveryCartAbandonedTopic -> {
                    val event = jsonCodec.fromJson<CartAbandonedEvent>(record.value())
                    recoveryScheduler.schedule(event)
                }
                config.recoveryAttemptsTopic -> {
                    val event = jsonCodec.fromJson<RecoveryAttemptDueEvent>(record.value())
                    dueAttemptExecutor.execute(event)
                }
                else -> {
                    outcome = "ignored"
                }
            }
        } catch (error: Exception) {
            outcome = "error"
            throw error
        } finally {
            recoveryMetrics.recordKafkaProcessed(record.topic(), outcome, elapsedMs(startedAt))
        }
    }

    private fun routeCommerceEvent(record: ConsumerRecord<String, String>) {
        val node = jsonCodec.objectMapper.readTree(record.value())
        when {
            node.has("mutationType") -> {
                producer.publishRawJson(config.recoveryCartMutationsTopic, record.key(), record.value())
                recoveryMetrics.recordKafkaRouted(record.topic(), config.recoveryCartMutationsTopic, "success")
            }
            node.has("stateType") -> {
                producer.publishRawJson(config.recoveryCartStateEventsTopic, record.key(), record.value())
                recoveryMetrics.recordKafkaRouted(record.topic(), config.recoveryCartStateEventsTopic, "success")
            }
            else -> {
                recoveryMetrics.recordKafkaRouted(record.topic(), "unrouted", "ignored")
                logger.warn("Ignoring commerce event without mutationType/stateType key={} value={}", record.key(), record.value())
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Double = (System.nanoTime() - startedAt) / 1_000_000.0
}
