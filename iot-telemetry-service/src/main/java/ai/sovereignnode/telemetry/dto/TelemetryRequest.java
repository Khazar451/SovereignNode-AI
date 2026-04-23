package ai.sovereignnode.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Builder;

import java.time.Instant;

/**
 * <h2>TelemetryRequest – Inbound REST Payload (Immutable Record)</h2>
 *
 * <p>Represents the JSON body received by the REST ingest endpoint. Declared
 * as a Java {@code record} (immutable by default) to make the contract explicit:
 * this object is created once from the HTTP body and never mutated.
 *
 * <p>Validation constraints mirror those on {@link ai.sovereignnode.telemetry.model.SensorReading}
 * but are kept here separately to:
 * <ul>
 *   <li>Isolate the API contract from the persistence schema.</li>
 *   <li>Allow independent versioning of the REST DTO and MongoDB model.</li>
 *   <li>Enable field renaming on the wire ({@code snake_case}) vs. in Java ({@code camelCase}).</li>
 * </ul>
 *
 * <p>Annotated with {@code @Builder} via Lombok record support so factory
 * methods in tests remain readable.
 */
@Builder
public record TelemetryRequest(

        /**
         * Unique hardware identifier for the sensor.
         * Wire name: {@code sensor_id}
         */
        @JsonProperty("sensor_id")
        @NotBlank(message = "sensor_id is required")
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_\\-]+$",
                 message = "sensor_id contains invalid characters")
        String sensorId,

        /**
         * ISO-8601 UTC timestamp of the observation, set by the edge device.
         * Wire name: {@code timestamp}
         */
        @JsonProperty("timestamp")
        @NotNull(message = "timestamp is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    timezone = "UTC")
        Instant timestamp,

        /**
         * Temperature reading in degrees Celsius.
         * Wire name: {@code temperature}
         */
        @JsonProperty("temperature")
        @NotNull(message = "temperature is required")
        @DecimalMin("-50.0") @DecimalMax("1200.0")
        Double temperature,

        /**
         * Vibration reading in mm/s RMS.
         * Wire name: {@code vibration}
         */
        @JsonProperty("vibration")
        @NotNull(message = "vibration is required")
        @DecimalMin("0.0") @DecimalMax("100.0")
        Double vibration,

        /**
         * Sensor operational status string (firmware-defined).
         * Wire name: {@code status}
         */
        @JsonProperty("status")
        @NotBlank(message = "status is required")
        @Size(max = 32)
        String status

) {}
