package com.abandonedcart.recovery.kafka

import com.abandonedcart.recovery.AppConfig
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

class KafkaLoggingConsumer(
    private val config: AppConfig,
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
                        logger.info(
                            "Kafka message topic={} key={} value={}",
                            record.topic(),
                            record.key(),
                            record.value(),
                        )
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
    }
}

