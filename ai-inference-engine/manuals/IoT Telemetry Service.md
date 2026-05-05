---
title: IoT Telemetry Service
tags: [sovereignnode, java, spring-boot, mongodb, rabbitmq, telemetry]
created: 2026-05-04
updated: 2026-05-04
type: engineering-note
related: ["[[AI Inference Engine]]", "[[SovNode Dashboard]]", "[[SovereignNode AI — Index]]"]
---

# ⚙️ IoT Telemetry Service

> [!NOTE] What Is This?
> The **iot-telemetry-service** is the Java backbone of SovereignNode. It is a **Spring Boot 3.3** service running on **Java 21** that receives high-frequency sensor readings over HTTP, persists them to MongoDB, fans them out to RabbitMQ for downstream consumers, and automatically dispatches diagnostic requests to the [[AI Inference Engine]] whenever a vibration anomaly is detected — all without blocking the sensor client.

**Location:** `SovereignNode-AI/iot-telemetry-service/`
**Language:** Java 21
**Framework:** Spring Boot 3.3 (MVC + Data MongoDB + AMQP)
**Exposes:** REST API on port `8080`
**Calls:** [[AI Inference Engine]] on port `8000`
**Polled by:** [[SovNode Dashboard]] on port `5173`

---

## Package Structure

```
ai.sovereignnode.telemetry
├── TelemetryServiceApplication.java     Entry point
├── controller/
│   └── TelemetryController.java         REST API endpoints
├── service/
│   └── TelemetryIngestionService.java   Core business logic + anomaly detection
├── client/
│   ├── AiInferenceClient.java           JSON contract types (records)
│   └── AiInferenceService.java          HTTP client to Python FastAPI
├── messaging/
│   └── TelemetryProducerService.java    RabbitMQ publisher
├── model/
│   └── SensorReading.java               MongoDB document + AMQP payload
├── repository/
│   └── SensorReadingRepository.java     Spring Data MongoDB queries
├── dto/
│   ├── TelemetryRequest.java            Inbound REST payload (record)
│   └── TelemetryResponse.java           Outbound acknowledgement (record)
├── config/
│   ├── AsyncConfig.java                 Java 21 virtual thread executor
│   ├── RabbitMQConfig.java              Full AMQP topology declaration
│   └── BrokerProperties.java            Typesafe broker config properties
└── exception/
    └── GlobalExceptionHandler.java      RFC 9457 Problem Detail responses
```

---

## `TelemetryServiceApplication.java` — Bootstrap

Four annotations activate the full platform at startup:

| Annotation | Effect |
|---|---|
| `@SpringBootApplication` | Standard Spring Boot autoconfiguration |
| `@EnableAsync` | Activates the virtual-thread `@Async` executor from `AsyncConfig` |
| `@EnableMongoAuditing` | Auto-populates `@CreatedDate` / `@LastModifiedDate` on every MongoDB save |
| `@ConfigurationPropertiesScan` | Discovers `BrokerProperties` without needing `@EnableConfigurationProperties` |

In `application.yml`, `spring.threads.virtual.enabled: true` enables **Java 21 Project Loom virtual threads** for all Tomcat request handling — every HTTP request runs on a lightweight virtual thread (~1 KB heap stack) rather than a platform thread (~1 MB stack).

---

## `SensorReading.java` — Domain Model

The central data object. Serves **dual duty**:

1. **MongoDB persistent document** — collection: `sensor_readings`
2. **AMQP message payload** — serialized as JSON and published to RabbitMQ (language-agnostic)

### Fields

| Field | Type | Constraint | Notes |
|---|---|---|---|
| `id` | `String` | Auto-generated | MongoDB ObjectId hex string |
| `sensorId` | `String` | 3–64 chars, alphanumeric | e.g. `TURBINE-42-VIBR` |
| `timestamp` | `Instant` | Not null | **Device-set** UTC time — not replaced with server clock |
| `temperature` | `Double` | −50 → 1200 °C | ISO industrial range |
| `vibration` | `Double` | 0 → 100 mm/s | ISO 10816 RMS velocity |
| `status` | `String` | ≤32 chars | Firmware-defined: `NOMINAL`, `WARNING`, `CRITICAL` |
| `ingestedAt` | `Instant` | Auto via `@CreatedDate` | Server-side wall-clock ingestion time |
| `lastModifiedAt` | `Instant` | Auto via `@LastModifiedDate` | Last update wall-clock |
| `version` | `Long` | Auto via `@Version` | Optimistic concurrency guard |

### MongoDB Compound Indexes

```
(sensor_id, timestamp) ASC   →  optimises per-sensor time-series range queries
(status,    timestamp) DESC  →  accelerates alert dashboard status-filtered feed
```

---

## `TelemetryController.java` — REST API

**Base path:** `/api/v1/telemetry`

All endpoints return HTTP 202 Accepted for ingest (not 201) — signalling the payload has been durably queued but AI processing is still in progress. Runs on Java 21 virtual threads. All inputs validated with Bean Validation (`@Valid`) before reaching the service layer.

### API Surface

| Method | Path | Returns | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/telemetry` | 202 `TelemetryResponse` | Ingest a single sensor reading |
| `GET` | `/api/v1/telemetry/sensor/{sensorId}` | 200 `Page<SensorReading>` | Paginated history per sensor, newest first |
| `GET` | `/api/v1/telemetry/sensor/{sensorId}/window` | 200 `List<SensorReading>` | Time-windowed readings between `from` and `to` |
| `GET` | `/api/v1/telemetry/status/{status}` | 200 `Page<SensorReading>` | Status-filtered alert feed |

**Swagger UI** auto-generated at `/swagger-ui.html` via SpringDoc OpenAPI.

### Example Ingest Payload

```json
{
  "sensor_id":   "TURBINE-01-VIBR",
  "timestamp":   "2026-05-04T17:00:00.000Z",
  "temperature": 87.4,
  "vibration":   9.5,
  "status":      "CRITICAL"
}
```

### Example Response

```json
{
  "id":           "663f4e2a1b2c3d4e5f6a7b8c",
  "sensor_id":    "TURBINE-01-VIBR",
  "ingested_at":  "2026-05-04T17:00:00.123Z",
  "broker_status": "QUEUED",
  "message":      "Reading persisted and enqueued for AI processing"
}
```

---

## `TelemetryIngestionService.java` — Core Business Logic

The most critical class. Every inbound sensor reading passes through a **5-step pipeline**:

**Step 1 — Map DTO → Domain Object**
Maps `TelemetryRequest` fields into a `SensorReading` builder. The two types are kept separate so the REST API contract and the persistence schema can evolve independently.

**Step 2 — Persist to MongoDB**
`repository.save(reading)` is a blocking call, but on a virtual thread this is perfectly safe — the virtual thread parks during the I/O, freeing the underlying OS carrier thread immediately.

**Step 3 — Async publish to RabbitMQ**
`producerService.publishReading(saved)` is called without `await`. It returns a `CompletableFuture<String>` and runs on a virtual thread. A `whenComplete` callback logs broker NACK failures.

**Step 4 — Async anomaly check + AI dispatch**
`processAndCheckAnomaly(saved)` is annotated `@Async` and runs on the virtual-thread executor. The HTTP response is already sent to the sensor client before this method starts.

**Step 5 — Return HTTP 202**
`TelemetryResponse` is built from the saved document and returned immediately.

### Anomaly Detection Logic

The vibration threshold is **7.1 mm/s RMS** — the ISO 10816-3 Class III alarm limit for industrial rotating machinery. Configurable via `ai.anomaly.vibration-threshold-mms`.

When a reading exceeds this threshold:

1. `anomalyDetectedCounter` is incremented (Micrometer)
2. A natural-language diagnostic query is assembled:

```
"Sensor 'PUMP-001' reported vibration of 9.50 mm/s RMS, significantly above the
alarm threshold of 7.10 mm/s. Temperature at the time was 65.0 degrees Celsius
and operational status was 'CRITICAL'. Based on the maintenance manual, what are
the most likely root causes and what immediate corrective actions should be taken?"
```

3. `AiInferenceService.generateInsight()` is called — blocks the virtual thread during the HTTP round-trip
4. The full structured insight is logged at WARN level
5. On failure, `aiInsightFailureCounter` is incremented and the error is logged — the anomaly record is never lost

### Micrometer Metrics

| Metric | Tracks |
|---|---|
| `telemetry.anomaly.detected` | Readings that crossed the vibration threshold |
| `telemetry.ai.insight.success` | Successful AI engine calls |
| `telemetry.ai.insight.failure` | Failed AI engine calls (down, timeout, contract error) |

---

## `AiInferenceClient.java` — JSON Contract

A pure data-contract class containing nested Java 21 **records** that mirror the Python Pydantic models exactly:

| Java Record | Python Pydantic Model |
|---|---|
| `RawTelemetry` | `RawTelemetry` |
| `AiInsightRequest` | `InsightRequest` |
| `AiInsightResponse` | `InsightResponse` |
| `AiInferenceException` | (custom RuntimeException) |

All fields use `@JsonProperty` for `snake_case` wire names, matching the Python API contract byte-for-byte.

---

## `AiInferenceService.java` — HTTP Client

Uses **Spring 6's `RestClient`** — the modern synchronous replacement for `RestTemplate`. The design rationale: because the service runs on Java 21 virtual threads, blocking I/O is cheap. `RestClient` gives a clean, readable API without the Reactor complexity of `WebClient`.

### Timeouts

| Timeout | Value | Reason |
|---|---|---|
| Connect | 2 seconds | Fail fast if the Python engine is unreachable |
| Read | 60 seconds | LLM inference on CPU can be very slow |

### Error Handling

| HTTP Status | Treatment | Reason |
|---|---|---|
| 4xx | Fatal `AiInferenceException` | Indicates a request contract mismatch |
| 5xx | Transient `AiInferenceException` | Engine may still be loading the model |
| Network/IO | Wrapped `AiInferenceException` | With full context for the caller |

Every call is wrapped in `Timer.record()` — the metric `ai.inference.call.duration` tracks end-to-end Python call latency, scraped by Prometheus via the Actuator endpoint.

---

## `TelemetryProducerService.java` — RabbitMQ Publisher

Publishes each `SensorReading` to the topic exchange as JSON using `RabbitTemplate`. Routing key format:

```
sensor.<sensorId>.<status>
```

**Examples:**

```
sensor.PUMP-001.CRITICAL       → matches sensor.# and sensor.*.CRITICAL
sensor.TURBINE-42-VIBR.NOMINAL → matches sensor.# and sensor.TURBINE-42-VIBR.*
```

**Publisher confirms** are registered at startup:
- Broker **ACK** → logged at DEBUG
- Broker **NACK** → logged at ERROR + `publishFailureCounter.increment()`
- **Returned message** (no binding matched) → logged at WARN + failure counter

A `publishBatch()` method handles lists of readings with per-message error isolation (one failure doesn't abort the rest).

### Metrics

| Metric | Tracks |
|---|---|
| `telemetry.publish.success` | Messages successfully ACK'd by broker |
| `telemetry.publish.failure` | NACK or returned messages |
| `telemetry.publish.duration` | End-to-end AMQP publish latency |

---

## `RabbitMQConfig.java` — AMQP Topology

Full RabbitMQ topology declared as Spring beans:

```
  [Producer]
      |  routing key: sensor.<id>.<status>
      v
  telemetry.exchange         (durable TopicExchange)
      |  binding: sensor.#
      v
  telemetry.ingest.queue     (durable, x-message-ttl = 60 000 ms)
      |  on reject / TTL expiry
      v
  telemetry.dlx              (Dead-Letter Exchange)
      v
  telemetry.dl.queue         (durable — for manual inspection)
```

### Key Design Decisions

> [!TIP] Durable Queues
> All queues and exchanges are durable — they survive broker restarts. Critical for edge deployments where RabbitMQ may run on unreliable industrial hardware.

> [!TIP] Dead-Letter Exchange
> Poison messages (rejected or TTL-expired) are routed to `telemetry.dl.queue` for manual inspection rather than silently dropped. This prevents silent data loss in production.

> [!TIP] Language-Agnostic Messages
> `Jackson2JsonMessageConverter` serializes all AMQP messages as JSON. Downstream Python or Go consumers can read them without a Java deserializer.

| Component | Name | Config |
|---|---|---|
| Exchange | `telemetry.exchange` | Durable TopicExchange |
| Main queue | `telemetry.ingest.queue` | Durable, TTL=60s, DLX configured |
| Dead-letter exchange | `telemetry.dlx` | Durable DirectExchange |
| Dead-letter queue | `telemetry.dl.queue` | Durable, for manual inspection |
| Message format | JSON | `Jackson2JsonMessageConverter` |
| Consumer ACK mode | Manual | No silent message loss on consumer crash |
| Prefetch | 10 | Back-pressure: consume 10 messages at a time |

---

## `AsyncConfig.java` — Java 21 Virtual Threads

```java
@Bean(name = "taskExecutor")
public Executor virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor(); // Java 21 Project Loom
}
```

This single bean makes every `@Async` annotated method (RabbitMQ publish, AI analysis) run on a Java 21 virtual thread. Virtual threads:

- Cost ~1 KB of heap per thread (vs ~1 MB for platform threads)
- Park during blocking I/O without consuming an OS thread
- Enable tens of thousands of concurrent in-flight operations

A secondary `boundedExecutor` bean (core=4, max=16, queue=500) is available for tasks that need controlled parallelism, injected with `@Async("boundedExecutor")`.

---

## `SensorReadingRepository.java` — MongoDB Queries

Extends `MongoRepository<SensorReading, String>` — Spring Data generates all implementations.

| Method | Purpose |
|---|---|
| `findBySensorIdOrderByTimestampDesc` | Paginated per-sensor history |
| `findBySensorIdAndTimeRange` | Time-windowed readings for rolling anomaly windows |
| `findByStatusOrderByTimestampDesc` | Status-filtered alert feed |
| `countBySensorIdAndTimestampBetween` | Detect silent sensors or message storms |
| `findOverTemperatureInWindow` | Thermal anomaly detection across all sensors |
| `findOverVibrationInWindow` | Vibration anomaly detection across all sensors |
| `countByTimestampBefore` | Archival job helper |

> [!TIP] Why Not Reactive MongoDB?
> Blocking `MongoRepository` is used instead of `ReactiveMongoRepository` because virtual threads make blocking I/O cheap. This avoids Project Reactor complexity while achieving the same concurrency profile.

---

## `GlobalExceptionHandler.java` — RFC 9457 Errors

All errors return structured **RFC 9457 Problem Detail** JSON — machine-parseable by all API consumers:

```json
{
  "type":       "https://sovereignnode.ai/errors/validation-failed",
  "title":      "Validation Failed",
  "status":     400,
  "detail":     "Request body contains one or more invalid fields",
  "timestamp":  "2026-05-04T17:00:00Z",
  "violations": { "sensorId": "sensor_id is required" }
}
```

Three handled cases:
- `MethodArgumentNotValidException` → 400 (Bean Validation on request body)
- `ConstraintViolationException` → 400 (validation on path/query params)
- `Exception` → 500 (unhandled fallback)

---

## `application.yml` — Key Configuration Reference

| Setting | Value | Purpose |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.threads.virtual.enabled` | `true` | Java 21 virtual threads globally |
| `server.shutdown` | `graceful` | Drain in-flight requests on SIGTERM |
| `spring.data.mongodb.uri` | env `MONGODB_URI` | MongoDB connection string |
| `spring.rabbitmq.publisher-confirm-type` | `correlated` | Per-message broker ACK |
| `spring.rabbitmq.listener.simple.acknowledge-mode` | `manual` | No silent message loss |
| `ai.anomaly.vibration-threshold-mms` | `7.1` | ISO 10816-3 Class III alarm limit |
| `ai.inference.base-url` | env `AI_INFERENCE_BASE_URL` | Python engine URL |
| `ai.inference.connect-timeout-seconds` | `2` | Fail fast if engine unreachable |
| `ai.inference.read-timeout-seconds` | `60` | Allow slow CPU LLM inference |
| `management.endpoints.web.exposure.include` | `health, metrics, prometheus` | Observability |

---

## Maven Dependencies (Key)

| Dependency | Version | Purpose |
|---|---|---|
| `spring-boot-starter-web` | 3.3.0 | REST API + embedded Tomcat |
| `spring-boot-starter-data-mongodb` | 3.3.0 | MongoDB persistence |
| `spring-boot-starter-amqp` | 3.3.0 | RabbitMQ messaging |
| `spring-boot-starter-actuator` | 3.3.0 | Health + metrics endpoints |
| `micrometer-registry-prometheus` | 1.13.0 | Prometheus scrape endpoint |
| `springdoc-openapi-starter-webmvc-ui` | 2.5.0 | Swagger UI |
| `lombok` | managed | Boilerplate reduction |
| `jackson-datatype-jsr310` | managed | ISO-8601 `Instant` serialization |
| `testcontainers` (mongodb + rabbitmq) | 1.19.8 | Integration tests with real services |

---

## Related Notes

- [[AI Inference Engine]] — called by this service via `POST /generate-insight` on anomaly
- [[SovNode Dashboard]] — polls this service's REST API every 2 seconds
- [[SovereignNode AI — Index]] — platform overview

---

#sovereignnode #java #spring-boot #mongodb #rabbitmq #virtual-threads #telemetry #iot
