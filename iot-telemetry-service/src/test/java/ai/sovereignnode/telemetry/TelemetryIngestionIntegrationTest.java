package ai.sovereignnode.telemetry;

import ai.sovereignnode.telemetry.model.SensorTelemetry;
import ai.sovereignnode.telemetry.repository.TelemetryRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@WireMockTest(httpPort = 8081)
public class TelemetryIngestionIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @Container
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:3-management");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TelemetryRepository telemetryRepository;

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQContainer::getAmqpPort);
        registry.add("ai.inference.base-url", () -> "http://localhost:8081");
    }

    @Test
    void testCriticalTelemetryIngestionFlow() {
        // 1. Mock the Python AI API
        stubFor(post(urlEqualTo("/generate-insight"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"insight\": \"Test Insight\", \"confidence_score\": 0.95, \"sources_referenced\": []}")));

        // 2. Prepare CRITICAL payload
        Map<String, Object> payload = Map.of(
                "sensorId", "VIB-MTR-999",
                "timestamp", LocalDateTime.now().toString(),
                "vibration_hz", 8.5,
                "temperature_c", 45.0,
                "status", "CRITICAL"
        );

        // 3. Send POST request
        ResponseEntity<Void> response = restTemplate.postForEntity("/api/v1/telemetry", payload, Void.class);

        // 4. Assert 202 Accepted (Async processing)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 5. Verify saved to MongoDB
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(telemetryRepository.findBySensorId("VIB-MTR-999")).isNotEmpty();
        });

        // 6. Verify WireMock received the AI diagnostic request
        verify(postRequestedFor(urlEqualTo("/generate-insight"))
                .withRequestBody(containing("VIB-MTR-999")));
    }
}
