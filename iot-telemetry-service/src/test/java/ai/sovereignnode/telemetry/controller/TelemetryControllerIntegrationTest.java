package ai.sovereignnode.telemetry.controller;

import ai.sovereignnode.telemetry.dto.TelemetryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * <h2>TelemetryControllerIntegrationTest</h2>
 *
 * <p>Full integration test using Testcontainers to spin up real MongoDB and
 * RabbitMQ instances inside Docker. No mocking of infrastructure.
 *
 * <p>Tests verify the complete flow: HTTP → service → MongoDB persist → RabbitMQ publish.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TelemetryControllerIntegrationTest {

    // ─── Testcontainers ───────────────────────────────────────────────────

    @Container
    static MongoDBContainer mongoDBContainer =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    static RabbitMQContainer rabbitMQContainer =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",   mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.rabbitmq.host",       rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port",       rabbitMQContainer::getAmqpPort);
        registry.add("spring.rabbitmq.username",   () -> "guest");
        registry.add("spring.rabbitmq.password",   () -> "guest");
    }

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ─── Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/telemetry → 202 Accepted with document ID")
    void ingestReading_whenValid_returns202() throws Exception {
        TelemetryRequest request = new TelemetryRequest(
                "TURBINE-01-TEMP",
                Instant.parse("2026-04-23T17:00:00.000Z"),
                87.4,
                3.21,
                "NOMINAL"
        );

        mockMvc.perform(post("/api/v1/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.sensor_id").value("TURBINE-01-TEMP"))
                .andExpect(jsonPath("$.broker_status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/v1/telemetry → 400 Bad Request when sensor_id is blank")
    void ingestReading_whenSensorIdBlank_returns400() throws Exception {
        String invalidJson = """
                {
                  "sensor_id":   "",
                  "timestamp":   "2026-04-23T17:00:00.000Z",
                  "temperature": 87.4,
                  "vibration":   3.21,
                  "status":      "NOMINAL"
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.sensorId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/telemetry → 400 when temperature out of range")
    void ingestReading_whenTempOutOfRange_returns400() throws Exception {
        String invalidJson = """
                {
                  "sensor_id":   "SENSOR-99",
                  "timestamp":   "2026-04-23T17:00:00.000Z",
                  "temperature": 9999.9,
                  "vibration":   0.5,
                  "status":      "NOMINAL"
                }
                """;

        mockMvc.perform(post("/api/v1/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/telemetry/sensor/{sensorId} → 200 OK with page structure")
    void getSensorReadings_returnsPagedResult() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry/sensor/TURBINE-01-TEMP")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
