package ai.sovereignnode.telemetry.client;

import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInferenceException;
import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInsightRequest;
import ai.sovereignnode.telemetry.client.AiInferenceClient.AiInsightResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * <h2>AiInferenceService – HTTP Client for the Python FastAPI Inference Engine</h2>
 *
 * <p>Uses Spring 6's {@link RestClient} (the modern, fluent replacement for
 * {@code RestTemplate}) to call the Python AI inference engine.
 *
 * <h3>Why RestClient instead of WebClient?</h3>
 * <p>Because we're running on Java 21 virtual threads, blocking I/O is cheap.
 * {@code RestClient} gives us a synchronous, readable API without the Reactor
 * complexity of {@code WebClient}. The virtual thread blocks during the HTTP
 * call, freeing the OS-level carrier thread immediately.
 *
 * <h3>Configuration</h3>
 * <p>Override {@code ai.inference.base-url} in {@code application.yml} or via
 * the {@code AI_INFERENCE_BASE_URL} environment variable.
 */
@Service
@Slf4j
public class AiInferenceService {

    private final RestClient restClient;
    private final Timer inferenceCallTimer;

    public AiInferenceService(
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${ai.inference.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.inference.connect-timeout-seconds:2}") int connectTimeoutSeconds,
            @Value("${ai.inference.read-timeout-seconds:60}") int readTimeoutSeconds
    ) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                // Note: Use a custom ClientHttpRequestFactory for fine-grained timeouts
                // (e.g., SimpleClientHttpRequestFactory or Apache HttpComponents).
                .build();

        this.inferenceCallTimer = Timer.builder("ai.inference.call.duration")
                .description("End-to-end latency of calls to the Python inference engine")
                .register(meterRegistry);

        log.info("AiInferenceService configured — base-url={} connect={}s read={}s",
                 baseUrl, connectTimeoutSeconds, readTimeoutSeconds);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Sends an anomaly-enriched telemetry payload to the Python inference engine
     * and returns the AI-generated diagnostic insight.
     *
     * <p>This method blocks the calling virtual thread during the HTTP round-trip.
     * Call it from an {@code @Async} context to keep the main event loop free.
     *
     * @param request Fully-populated insight request with sensor telemetry and query.
     * @return The AI engine's structured insight response.
     * @throws AiInferenceException if the HTTP call fails (network error, 4xx, 5xx).
     */
    public AiInsightResponse generateInsight(AiInsightRequest request) {
        log.debug("Calling AI inference for sensorId={} anomalyType={}",
                  request.sensorId(), request.anomalyType());

        return inferenceCallTimer.record(() -> {
            try {
                AiInsightResponse response = restClient.post()
                        .uri("/generate-insight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        // Treat 4xx client errors as fatal — these indicate a contract mismatch
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            throw new AiInferenceException(
                                    String.format("AI engine rejected request: HTTP %d for sensorId=%s",
                                                  res.getStatusCode().value(), request.sensorId()),
                                    null
                            );
                        })
                        // Treat 5xx as transient — caller may retry
                        .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                            throw new AiInferenceException(
                                    String.format("AI engine server error: HTTP %d — inference may be loading",
                                                  res.getStatusCode().value()),
                                    null
                            );
                        })
                        .body(AiInsightResponse.class);

                log.info(
                    "AI insight received for sensorId={} confidence={} inferenceMs={} sources={}",
                    request.sensorId(),
                    response != null ? response.confidenceScore() : "N/A",
                    response != null ? response.inferenceTimeMs() : "N/A",
                    response != null ? response.sourcesReferenced() : "[]"
                );
                return response;

            } catch (AiInferenceException ex) {
                throw ex;  // Re-throw structured errors unchanged
            } catch (Exception ex) {
                throw new AiInferenceException(
                        "Unexpected error calling AI inference engine for sensorId=" + request.sensorId(),
                        ex
                );
            }
        });
    }
}
