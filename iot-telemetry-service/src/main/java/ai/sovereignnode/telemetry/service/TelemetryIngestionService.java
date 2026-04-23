package ai.sovereignnode.telemetry.service;

import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInsightRequest;
import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInsightResponse;
import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInferenceException;
import ai.sovereignnode.telemetry.client.AiInferenceClient.RawTelemetry;
import ai.sovereignnode.telemetry.client.AiInferenceService;
import ai.sovereignnode.telemetry.dto.TelemetryRequest;
import ai.sovereignnode.telemetry.dto.TelemetryResponse;
import ai.sovereignnode.telemetry.messaging.TelemetryProducerService;
import ai.sovereignnode.telemetry.model.SensorReading;
import ai.sovereignnode.telemetry.repository.SensorReadingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <h2>TelemetryIngestionService – Core Business Logic (Phase 3: AI Integration)</h2>
 *
 * <p>Orchestrates the three-phase telemetry pipeline:
 * <ol>
 *   <li><b>Persist</b> – map the validated DTO to a MongoDB document.</li>
 *   <li><b>Publish</b> – async fan-out to RabbitMQ for downstream consumers.</li>
 *   <li><b>Anomaly Analysis</b> – if vibration exceeds the configured threshold,
 *       asynchronously call the Python AI inference engine for a diagnostic insight
 *       and log the result. This never blocks the HTTP caller.</li>
 * </ol>
 *
 * <h3>Vibration Anomaly Threshold</h3>
 * <p>Configurable via {@code ai.anomaly.vibration-threshold-mms} in
 * {@code application.yml}. Default: {@code 7.1 mm/s} (ISO 10816-3 Class III
 * alarm limit for industrial rotating machinery).
 *
 * <h3>Concurrency</h3>
 * <p>{@link #processAndCheckAnomaly} is annotated {@code @Async} and runs
 * on the virtual-thread executor defined in
 * {@link ai.sovereignnode.telemetry.config.AsyncConfig}. The ingest HTTP call
 * returns immediately after MongoDB persistence; AI analysis proceeds in the
 * background without any latency impact on the sensor client.
 */
@Service
@Slf4j
public class TelemetryIngestionService {

    // ── Dependencies (constructor-injected via Lombok @RequiredArgsConstructor
    //    is NOT used here because we also inject @Value fields) ──────────────
    private final SensorReadingRepository repository;
    private final TelemetryProducerService producerService;
    private final AiInferenceService        aiInferenceService;

    /** Vibration alarm threshold in mm/s RMS. Overridable per deployment. */
    private final double vibrationThresholdMms;

    // ── Metrics ───────────────────────────────────────────────────────────────
    private final Counter anomalyDetectedCounter;
    private final Counter aiInsightSuccessCounter;
    private final Counter aiInsightFailureCounter;

    public TelemetryIngestionService(
            SensorReadingRepository repository,
            TelemetryProducerService producerService,
            AiInferenceService aiInferenceService,
            MeterRegistry meterRegistry,
            @Value("${ai.anomaly.vibration-threshold-mms:7.1}") double vibrationThresholdMms
    ) {
        this.repository            = repository;
        this.producerService       = producerService;
        this.aiInferenceService    = aiInferenceService;
        this.vibrationThresholdMms = vibrationThresholdMms;

        this.anomalyDetectedCounter = Counter.builder("telemetry.anomaly.detected")
                .description("Number of sensor readings that exceeded the vibration anomaly threshold")
                .register(meterRegistry);

        this.aiInsightSuccessCounter = Counter.builder("telemetry.ai.insight.success")
                .description("Number of successful AI diagnostic insight calls")
                .register(meterRegistry);

        this.aiInsightFailureCounter = Counter.builder("telemetry.ai.insight.failure")
                .description("Number of failed AI diagnostic insight calls")
                .register(meterRegistry);
    }

    // ─── Ingest ───────────────────────────────────────────────────────────────

    /**
     * Persists a single sensor reading, enqueues it for AI streaming,
     * and asynchronously triggers anomaly analysis if the reading is critical.
     *
     * @param request Validated inbound DTO from the REST layer.
     * @return {@link TelemetryResponse} sent immediately after persistence.
     */
    public TelemetryResponse ingest(TelemetryRequest request) {
        log.debug("Ingesting reading for sensorId={} timestamp={}",
                  request.sensorId(), request.timestamp());

        // ── 1. Map DTO → domain document ──────────────────────────────────────
        SensorReading reading = SensorReading.builder()
                .sensorId(request.sensorId())
                .timestamp(request.timestamp())
                .temperature(request.temperature())
                .vibration(request.vibration())
                .status(request.status())
                .build();

        // ── 2. Persist to MongoDB (blocks virtual thread safely) ───────────────
        SensorReading saved = repository.save(reading);
        log.info("Persisted reading id={} sensorId={} vibration={}",
                 saved.getId(), saved.getSensorId(), saved.getVibration());

        // ── 3. Async publish to RabbitMQ ──────────────────────────────────────
        producerService.publishReading(saved)
                .whenComplete((routingKey, ex) -> {
                    if (ex != null) {
                        log.warn("Async publish failed for document id={}: {}",
                                 saved.getId(), ex.getMessage());
                    }
                });

        // ── 4. Async anomaly analysis via Python AI engine ─────────────────────
        //    Fire-and-forget: the HTTP response is sent before this completes.
        processAndCheckAnomaly(saved);

        // ── 5. Return immediate acknowledgement ────────────────────────────────
        return TelemetryResponse.builder()
                .id(saved.getId())
                .sensorId(saved.getSensorId())
                .ingestedAt(saved.getIngestedAt())
                .brokerStatus("QUEUED")
                .message("Reading persisted and enqueued for AI processing")
                .build();
    }

    // ─── Anomaly Detection + AI Analysis ─────────────────────────────────────

    /**
     * Checks whether the reading constitutes a vibration anomaly.
     * If it does, calls the Python AI inference engine asynchronously
     * to generate a diagnostic insight from the enterprise manuals.
     *
     * <p><b>This method runs on a Java 21 virtual thread</b> (via the
     * {@code taskExecutor} bean in {@link ai.sovereignnode.telemetry.config.AsyncConfig}).
     * It blocks during the HTTP call to the Python engine without consuming an
     * OS thread, allowing the application to handle thousands of concurrent
     * in-flight AI requests with minimal heap overhead.
     *
     * @param reading The newly persisted sensor reading to evaluate.
     * @return A {@link CompletableFuture} that completes when the AI call finishes
     *         (or when the anomaly check short-circuits with no anomaly found).
     */
    @Async
    public CompletableFuture<Void> processAndCheckAnomaly(SensorReading reading) {

        // ── Guard: only process readings above the anomaly threshold ───────────
        if (reading.getVibration() == null || reading.getVibration() <= vibrationThresholdMms) {
            log.debug("No anomaly: sensorId={} vibration={}mm/s (threshold={}mm/s)",
                      reading.getSensorId(), reading.getVibration(), vibrationThresholdMms);
            return CompletableFuture.completedFuture(null);
        }

        // ── Anomaly confirmed ──────────────────────────────────────────────────
        anomalyDetectedCounter.increment();
        log.warn(
            "ANOMALY DETECTED: sensorId={} vibration={}mm/s EXCEEDS threshold={}mm/s — "
            + "dispatching to AI inference engine",
            reading.getSensorId(), reading.getVibration(), vibrationThresholdMms
        );

        // ── Build AI insight request ───────────────────────────────────────────
        String naturalLanguageQuery = String.format(
            "Sensor '%s' reported vibration of %.2f mm/s RMS, significantly above the alarm "
            + "threshold of %.2f mm/s. Temperature at the time was %.1f degrees Celsius and "
            + "operational status was '%s'. Based on the maintenance manual, what are the most "
            + "likely root causes and what immediate corrective actions should be taken?",
            reading.getSensorId(),
            reading.getVibration(),
            vibrationThresholdMms,
            reading.getTemperature() != null ? reading.getTemperature() : 0.0,
            reading.getStatus()
        );

        AiInsightRequest aiRequest = new AiInsightRequest(
                reading.getSensorId(),
                "HIGH_VIBRATION",
                new RawTelemetry(
                        reading.getTemperature() != null ? reading.getTemperature() : 0.0,
                        reading.getVibration(),
                        reading.getTimestamp()
                ),
                naturalLanguageQuery
        );

        // ── Call Python AI engine (blocks this virtual thread, not an OS thread) ─
        try {
            AiInsightResponse response = aiInferenceService.generateInsight(aiRequest);
            aiInsightSuccessCounter.increment();

            if (response != null) {
                log.warn(
                    "=== AI DIAGNOSTIC INSIGHT =============================================\n"
                    + "  SensorId        : {}\n"
                    + "  VibrationReading: {} mm/s\n"
                    + "  ConfidenceScore : {}\n"
                    + "  InferenceTimeMs : {} ms\n"
                    + "  SourcesUsed     : {}\n"
                    + "  Insight         :\n{}\n"
                    + "======================================================================",
                    reading.getSensorId(),
                    reading.getVibration(),
                    response.confidenceScore(),
                    response.inferenceTimeMs(),
                    response.sourcesReferenced(),
                    response.insight()
                );
            }

        } catch (AiInferenceException ex) {
            aiInsightFailureCounter.increment();
            log.error(
                "AI inference call failed for sensorId={} — anomaly logged but no insight available. "
                + "Cause: {}",
                reading.getSensorId(), ex.getMessage()
            );
        }

        return CompletableFuture.completedFuture(null);
    }

    // ─── Query Helpers ────────────────────────────────────────────────────────

    /** Returns paginated readings for a specific sensor, most-recent first. */
    public Page<SensorReading> getReadingsBySensor(String sensorId, Pageable pageable) {
        return repository.findBySensorIdOrderByTimestampDesc(sensorId, pageable);
    }

    /** Returns readings within a time window for a specific sensor. */
    public List<SensorReading> getReadingsByTimeRange(String sensorId,
                                                       Instant from,
                                                       Instant to) {
        return repository.findBySensorIdAndTimeRange(sensorId, from, to);
    }

    /** Returns paginated readings filtered by operational status. */
    public Page<SensorReading> getReadingsByStatus(String status, Pageable pageable) {
        return repository.findByStatusOrderByTimestampDesc(status, pageable);
    }
}
