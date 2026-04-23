package ai.sovereignnode.telemetry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * <h2>SensorReading – MongoDB Document &amp; Serialisable Message Payload</h2>
 *
 * <p>A single observation emitted by an industrial IoT sensor. This class serves
 * dual duty as:
 * <ol>
 *   <li>The <b>MongoDB persistent document</b> (collection: {@code sensor_readings}).</li>
 *   <li>The <b>AMQP message payload</b> published to the RabbitMQ topic exchange
 *       so that downstream AI engines can consume it asynchronously.</li>
 * </ol>
 *
 * <h3>Compound Index Strategy</h3>
 * <ul>
 *   <li>{@code (sensor_id, timestamp) ASC} – optimises time-series range queries per sensor.</li>
 *   <li>{@code (status, timestamp) DESC} – accelerates alert dashboards filtering by status.</li>
 * </ul>
 *
 * <p>Implements {@link Serializable} so Spring AMQP can serialise it via
 * {@link org.springframework.amqp.support.converter.Jackson2JsonMessageConverter}
 * without additional configuration.
 */
@Document(collection = "sensor_readings")
@CompoundIndexes({
    @CompoundIndex(name = "idx_sensor_time",  def = "{'sensor_id': 1, 'timestamp': 1}"),
    @CompoundIndex(name = "idx_status_time", def = "{'status': 1,     'timestamp': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorReading implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ─── Persistence Identity ──────────────────────────────────────────────

    /** MongoDB document ID – auto-generated ObjectId string. */
    @Id
    private String id;

    // ─── Payload Fields ────────────────────────────────────────────────────

    /**
     * Unique hardware identifier of the sensor (e.g., "TURBINE-42-VIBR").
     * Must be between 3 and 64 characters; only alphanumerics, hyphens, underscores.
     */
    @NotBlank(message = "sensor_id must not be blank")
    @Size(min = 3, max = 64, message = "sensor_id length must be 3–64 characters")
    @Pattern(regexp = "^[A-Za-z0-9_\\-]+$",
             message = "sensor_id may only contain alphanumerics, hyphens, underscores")
    @Field("sensor_id")
    private String sensorId;

    /**
     * Epoch-aligned observation timestamp provided by the edge device.
     * Treated as immutable fact (do not replace with server-side wall clock).
     */
    @NotNull(message = "timestamp must not be null")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                timezone = "UTC")
    private Instant timestamp;

    /**
     * Temperature reading in degrees Celsius.
     * Industrial range assumed: −50 °C → 1 200 °C.
     */
    @NotNull(message = "temperature must not be null")
    @DecimalMin(value = "-50.0",  message = "temperature below operational minimum (−50 °C)")
    @DecimalMax(value = "1200.0", message = "temperature above operational maximum (1 200 °C)")
    private Double temperature;

    /**
     * Vibration reading in mm/s RMS (root-mean-square velocity).
     * ISO 10816 industrial machinery range: 0 → 100 mm/s.
     */
    @NotNull(message = "vibration must not be null")
    @DecimalMin(value = "0.0",   message = "vibration cannot be negative")
    @DecimalMax(value = "100.0", message = "vibration exceeds sensor upper-bound (100 mm/s)")
    private Double vibration;

    /**
     * Operational status string reported by the sensor firmware.
     * Expected values governed by the schema version; kept as free-form String
     * to support evolving firmware vocabularies without schema migration.
     */
    @NotBlank(message = "status must not be blank")
    @Size(max = 32, message = "status may not exceed 32 characters")
    private String status;

    // ─── Audit Fields (auto-populated by @EnableMongoAuditing) ───────────────

    /** Server-side wall-clock time when the document was first ingested. */
    @CreatedDate
    @Field("ingested_at")
    private Instant ingestedAt;

    /** Wall-clock time of the last document modification (if applicable). */
    @LastModifiedDate
    @Field("last_modified_at")
    private Instant lastModifiedAt;

    /** Version field – optimistic concurrency guard. */
    @Version
    private Long version;
}
