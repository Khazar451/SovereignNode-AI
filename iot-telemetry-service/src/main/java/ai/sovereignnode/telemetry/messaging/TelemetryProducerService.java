package ai.sovereignnode.telemetry.messaging;

import ai.sovereignnode.telemetry.config.BrokerProperties;
import ai.sovereignnode.telemetry.model.SensorReading;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * <h2>TelemetryProducerService – Async AMQP Message Publisher</h2>
 *
 * <p>Publishes {@link SensorReading} payloads to the RabbitMQ topic exchange
 * so that downstream AI inference engines can consume them at their own pace,
 * decoupling ingestion throughput from AI processing latency.
 *
 * <h3>Concurrency Model</h3>
 * <ul>
 *   <li>{@code @Async} methods return {@link CompletableFuture}, allowing the
 *       HTTP thread (virtual thread) to return an HTTP 202 Accepted immediately
 *       while the AMQP publish completes asynchronously.</li>
 *   <li>Publisher confirms (correlated) verify that the broker received each
 *       message; negative confirms are logged with the correlation ID for
 *       re-delivery or alerting.</li>
 * </ul>
 *
 * <h3>Routing Key Strategy</h3>
 * <p>The routing key is constructed as {@code sensor.<sensorId>.<status>} so
 * topic subscriptions can be scoped to specific sensors or specific statuses
 * without modifying producer code:
 * <pre>
 *   sensor.#              → all sensor messages (AI ingestion queue)
 *   sensor.*.CRITICAL     → only critical readings (alert queue)
 *   sensor.TURBINE-01.*   → all readings from a specific turbine
 * </pre>
 *
 * <h3>Observability</h3>
 * <p>Micrometer counters and timers track publish success/failure rates and
 * end-to-end publish latency, scraped by Prometheus.
 */
@Service
@Slf4j
public class TelemetryProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final BrokerProperties brokerProps;

    // ── Micrometer metrics ──────────────────────────────────────────────────
    private final Counter publishSuccessCounter;
    private final Counter publishFailureCounter;
    private final Timer   publishTimer;

    public TelemetryProducerService(RabbitTemplate rabbitTemplate,
                                    BrokerProperties brokerProps,
                                    MeterRegistry meterRegistry) {
        this.rabbitTemplate  = rabbitTemplate;
        this.brokerProps     = brokerProps;

        this.publishSuccessCounter = Counter.builder("telemetry.publish.success")
                .description("Number of sensor readings successfully published to RabbitMQ")
                .register(meterRegistry);

        this.publishFailureCounter = Counter.builder("telemetry.publish.failure")
                .description("Number of sensor reading publish failures")
                .register(meterRegistry);

        this.publishTimer = Timer.builder("telemetry.publish.duration")
                .description("End-to-end AMQP publish latency in milliseconds")
                .register(meterRegistry);

        // ── Publisher confirm callback ──────────────────────────────────────
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String correlationId = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                log.debug("Broker ACK received for correlation-id={}", correlationId);
            } else {
                log.error("Broker NACK received for correlation-id={} cause='{}'",
                          correlationId, cause);
                publishFailureCounter.increment();
            }
        });

        // ── Return callback (mandatory=true – fires when no queue can route) ─
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("Message returned: routingKey='{}' replyText='{}' body={}",
                     returned.getRoutingKey(),
                     returned.getReplyText(),
                     new String(returned.getMessage().getBody()));
            publishFailureCounter.increment();
        });
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /**
     * Publishes a {@link SensorReading} to the topic exchange asynchronously.
     *
     * <p>Routing key: {@code sensor.<sensorId>.<status>} (dots replace hyphens
     * to satisfy AMQP topic syntax requirements).
     *
     * @param reading The persisted sensor reading to broadcast.
     * @return A {@link CompletableFuture} with the routing key used, for caller tracing.
     */
    @Async
    public CompletableFuture<String> publishReading(SensorReading reading) {
        String routingKey = buildRoutingKey(reading);
        String correlationId = UUID.randomUUID().toString();

        log.debug("Publishing reading id={} sensorId={} routingKey='{}'",
                  reading.getId(), reading.getSensorId(), routingKey);

        return publishTimer.record(() -> {
            try {
                CorrelationData correlationData = new CorrelationData(correlationId);

                rabbitTemplate.convertAndSend(
                        brokerProps.getExchange(),
                        routingKey,
                        reading,
                        correlationData
                );

                publishSuccessCounter.increment();
                log.info("Published reading id={} sensorId={} routingKey='{}' correlationId={}",
                         reading.getId(), reading.getSensorId(), routingKey, correlationId);

                return CompletableFuture.completedFuture(routingKey);

            } catch (Exception ex) {
                publishFailureCounter.increment();
                log.error("Failed to publish reading id={} sensorId={} cause='{}'",
                          reading.getId(), reading.getSensorId(), ex.getMessage(), ex);
                return CompletableFuture.<String>failedFuture(ex);
            }
        });
    }

    /**
     * Batch-publishes a list of readings. Each publish is independent; partial
     * failures are logged but do not abort the remaining messages.
     *
     * @param readings Iterable of saved sensor readings.
     * @return Number of messages successfully enqueued.
     */
    @Async
    public CompletableFuture<Integer> publishBatch(Iterable<SensorReading> readings) {
        int successCount = 0;
        for (SensorReading reading : readings) {
            try {
                publishReading(reading).join();
                successCount++;
            } catch (Exception ex) {
                log.error("Batch: skipping reading id={} due to publish error: {}",
                          reading.getId(), ex.getMessage());
            }
        }
        return CompletableFuture.completedFuture(successCount);
    }

    // ─── Private Helpers ──────────────────────────────────────────────────

    /**
     * Constructs an AMQP topic-compatible routing key.
     *
     * <p>AMQP topics use {@code .} as the word delimiter. Sensor IDs and status
     * values may contain hyphens and underscores, which are safe, but spaces or
     * other special characters are replaced with underscores to avoid routing issues.
     */
    private String buildRoutingKey(SensorReading reading) {
        String safeSensorId = sanitise(reading.getSensorId());
        String safeStatus   = sanitise(reading.getStatus());
        return String.format("sensor.%s.%s", safeSensorId, safeStatus);
    }

    private static String sanitise(String value) {
        // Replace characters that are not AMQP word-safe with underscores
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
