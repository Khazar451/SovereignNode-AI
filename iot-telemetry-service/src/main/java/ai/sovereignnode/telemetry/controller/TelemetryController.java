package ai.sovereignnode.telemetry.controller;

import ai.sovereignnode.telemetry.dto.TelemetryRequest;
import ai.sovereignnode.telemetry.dto.TelemetryResponse;
import ai.sovereignnode.telemetry.model.SensorReading;
import ai.sovereignnode.telemetry.service.TelemetryIngestionService;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * <h2>TelemetryController – IoT Sensor Ingestion REST API</h2>
 *
 * <p>Exposes high-frequency IoT telemetry endpoints under {@code /api/v1/telemetry}.
 * All endpoints are designed for maximum throughput:
 * <ul>
 *   <li>POST operations return HTTP 202 Accepted rather than 201 Created, signalling
 *       that the payload has been durably queued but downstream AI processing is
 *       still in progress.</li>
 *   <li>Requests run on Java 21 virtual threads, eliminating OS-thread exhaustion
 *       under sustained high-frequency sensor bursts.</li>
 *   <li>Bean Validation ({@code @Valid}) rejects malformed payloads before they
 *       reach the service layer, protecting both MongoDB and RabbitMQ from noise.</li>
 * </ul>
 *
 * <h3>API Surface</h3>
 * <pre>
 *   POST   /api/v1/telemetry                         ingest single reading
 *   GET    /api/v1/telemetry/sensor/{sensorId}        paginated history per sensor
 *   GET    /api/v1/telemetry/sensor/{sensorId}/window time-windowed readings
 *   GET    /api/v1/telemetry/status/{status}          paginated by status (alert feed)
 * </pre>
 */
@RestController
@RequestMapping(value = "/api/v1/telemetry", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Telemetry Ingestion",
     description = "High-frequency IoT sensor telemetry ingestion and retrieval API")
public class TelemetryController {

    private final TelemetryIngestionService ingestionService;

    // ─── POST /api/v1/telemetry ────────────────────────────────────────────

    /**
     * Ingest a single sensor reading.
     *
     * <p>Returns HTTP 202 Accepted immediately after persisting to MongoDB and
     * enqueuing the message to RabbitMQ for async AI inference. The caller does
     * NOT wait for AI processing to complete.
     *
     * <p>Example body:
     * <pre>{@code
     * {
     *   "sensor_id":   "TURBINE-01-VIBR",
     *   "timestamp":   "2026-04-23T17:00:00.000Z",
     *   "temperature": 87.4,
     *   "vibration":   3.21,
     *   "status":      "NOMINAL"
     * }
     * }</pre>
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Timed(value = "telemetry.ingest.single", description = "Single reading ingest latency")
    @Operation(
        summary     = "Ingest a single sensor telemetry reading",
        description = "Persists the reading to MongoDB and enqueues it to RabbitMQ for AI processing. "
                    + "Returns 202 Accepted; AI inference is asynchronous.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Reading accepted and queued",
                     content = @Content(schema = @Schema(implementation = TelemetryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure – malformed payload",
                     content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal broker or database error",
                     content = @Content)
    })
    public ResponseEntity<TelemetryResponse> ingestReading(
            @Valid @RequestBody TelemetryRequest request) {

        log.info("Ingest request received: sensorId={} status={}",
                 request.sensorId(), request.status());

        TelemetryResponse response = ingestionService.ingest(request);
        return ResponseEntity.accepted().body(response);
    }

    // ─── GET /api/v1/telemetry/sensor/{sensorId} ──────────────────────────

    /**
     * Retrieve paginated historical readings for a specific sensor,
     * sorted descending by observation timestamp (most-recent first).
     */
    @GetMapping("/sensor/{sensorId}")
    @Timed(value = "telemetry.query.by_sensor", description = "Sensor history query latency")
    @Operation(
        summary     = "Get paginated readings for a sensor",
        description = "Returns historical readings for the given sensor ID, most-recent first.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated readings returned"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination parameters")
    })
    public ResponseEntity<Page<SensorReading>> getReadingsBySensor(
            @Parameter(description = "Unique sensor hardware ID", example = "TURBINE-01-VIBR")
            @PathVariable String sensorId,

            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (max 200)", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {

        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "timestamp"));
        return ResponseEntity.ok(ingestionService.getReadingsBySensor(sensorId, pageable));
    }

    // ─── GET /api/v1/telemetry/sensor/{sensorId}/window ──────────────────

    /**
     * Retrieve all readings for a sensor within a time window.
     * Useful for feeding rolling anomaly-detection windows to the AI engine on demand.
     */
    @GetMapping("/sensor/{sensorId}/window")
    @Timed(value = "telemetry.query.time_window", description = "Time-window query latency")
    @Operation(
        summary     = "Get readings for a sensor within a time window",
        description = "Returns all readings for the given sensor between 'from' (inclusive) and 'to' (exclusive). "
                    + "Timestamps must be ISO-8601 UTC strings.")
    public ResponseEntity<List<SensorReading>> getReadingsByTimeWindow(
            @PathVariable String sensorId,

            @Parameter(description = "Window start (ISO-8601 UTC)", example = "2026-04-23T00:00:00.000Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Window end (ISO-8601 UTC)", example = "2026-04-23T23:59:59.999Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }

        List<SensorReading> readings = ingestionService.getReadingsByTimeRange(sensorId, from, to);
        return ResponseEntity.ok(readings);
    }

    // ─── GET /api/v1/telemetry/status/{status} ────────────────────────────

    /**
     * Retrieve paginated readings filtered by operational status.
     * Primary feed for the alerting dashboard (e.g., status=CRITICAL).
     */
    @GetMapping("/status/{status}")
    @Timed(value = "telemetry.query.by_status", description = "Status filter query latency")
    @Operation(
        summary     = "Get paginated readings by status",
        description = "Returns readings matching the given status (e.g. NOMINAL, WARNING, CRITICAL), "
                    + "sorted descending by timestamp.")
    public ResponseEntity<Page<SensorReading>> getReadingsByStatus(
            @Parameter(description = "Sensor status value", example = "CRITICAL")
            @PathVariable String status,

            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {

        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "timestamp"));
        return ResponseEntity.ok(ingestionService.getReadingsByStatus(status, pageable));
    }
}
