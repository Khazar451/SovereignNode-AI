package ai.sovereignnode.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;

/**
 * <h2>TelemetryResponse – Outbound REST Payload (Immutable Record)</h2>
 *
 * <p>Returned by the ingest endpoint after successful processing. Contains
 * the MongoDB document ID and both the original sensor timestamp and the
 * server-side ingestion timestamp for client reconciliation.
 *
 * <p>{@code @JsonInclude(NON_NULL)} ensures optional fields (e.g., {@code message})
 * are omitted from the response body when null, keeping the payload lean.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryResponse(

        /** MongoDB-assigned document ObjectId as hex string. */
        @JsonProperty("id")
        String id,

        /** Original sensor timestamp echoed back for acknowledgement. */
        @JsonProperty("sensor_id")
        String sensorId,

        /** Server-side wall-clock time when the reading was ingested. */
        @JsonProperty("ingested_at")
        Instant ingestedAt,

        /** Human-readable status of the ingest operation. */
        @JsonProperty("broker_status")
        String brokerStatus,

        /** Optional human-readable message (e.g., routing key used). */
        @JsonProperty("message")
        String message

) {}
