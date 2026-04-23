package ai.sovereignnode.telemetry.client;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * <h2>AiInferenceClient – Java 21 RestClient Bridge to Python FastAPI</h2>
 *
 * <p>Calls the Python AI inference engine's {@code POST /generate-insight}
 * endpoint using Spring 6's synchronous {@link org.springframework.web.client.RestClient}.
 * All HTTP calls are made on virtual threads via {@code @Async}, so the caller
 * is never blocked on the OS-thread pool.
 *
 * <h3>JSON Contract</h3>
 * <pre>
 * Request  → AiInsightRequest  (sensor_id, anomaly_type, raw_telemetry, query)
 * Response ← AiInsightResponse (insight, confidence_score, sources_referenced,
 *                                inference_time_ms)
 * </pre>
 *
 * <h3>Resilience</h3>
 * <ul>
 *   <li>Connection timeout: 2 s — fail fast if the inference engine is down.</li>
 *   <li>Read timeout: 60 s — LLM inference on CPU can be slow; adjust per hardware.</li>
 *   <li>All HTTP errors are caught and surfaced as {@link AiInferenceException}
 *       so the caller can decide whether to retry or fall back gracefully.</li>
 * </ul>
 */
public class AiInferenceClient {

    // ─── Nested JSON record types ──────────────────────────────────────────────

    /**
     * Telemetry snapshot embedded inside the insight request.
     * Field names are snake_case to match the Python Pydantic schema exactly.
     */
    public record RawTelemetry(
            @JsonProperty("temperature")  double temperature,
            @JsonProperty("vibration")    double vibration,
            @JsonProperty("timestamp")    Instant timestamp
    ) {}

    /**
     * Full request payload sent to {@code POST /generate-insight}.
     */
    public record AiInsightRequest(
            @JsonProperty("sensor_id")     String sensorId,
            @JsonProperty("anomaly_type")  String anomalyType,
            @JsonProperty("raw_telemetry") RawTelemetry rawTelemetry,
            @JsonProperty("query")         String query
    ) {}

    /**
     * Response payload returned by the Python inference engine.
     * All fields are nullable — the client must handle partial responses gracefully.
     */
    public record AiInsightResponse(
            @JsonProperty("insight")             String insight,
            @JsonProperty("confidence_score")    double confidenceScore,
            @JsonProperty("sources_referenced")  List<String> sourcesReferenced,
            @JsonProperty("inference_time_ms")   double inferenceTimeMs
    ) {}

    /**
     * Unchecked exception thrown when the AI inference call fails.
     * Wraps the underlying HTTP or IO error with additional context.
     */
    public static class AiInferenceException extends RuntimeException {
        public AiInferenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
