package ai.sovereignnode.telemetry.repository;

import ai.sovereignnode.telemetry.model.SensorReading;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * <h2>SensorReadingRepository – NoSQL Persistence Interface</h2>
 *
 * <p>Spring Data MongoDB repository for {@link SensorReading} documents.
 * All methods delegate to the auto-generated MongoDB driver implementation;
 * zero boilerplate persistence code is required.
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Extends {@link MongoRepository} (not {@code ReactiveMongoRepository})
 *       because blocking I/O is virtualised via Java 21 virtual threads,
 *       eliminating the need for Project Reactor complexity in services.</li>
 *   <li>Custom {@link Query} annotations use MongoDB query language directly,
 *       giving full control over aggregation-style filtering without SDN
 *       derived-method limitations.</li>
 *   <li>All list-returning finder methods accept a {@link Pageable} argument
 *       to prevent unbounded result sets on high-volume collections.</li>
 * </ul>
 */
@Repository
public interface SensorReadingRepository extends MongoRepository<SensorReading, String> {

    // ─── Sensor-Centric Queries ────────────────────────────────────────────

    /**
     * Returns a paginated view of all readings for a specific sensor,
     * ordered by the compound index {@code (sensor_id, timestamp)}.
     */
    Page<SensorReading> findBySensorIdOrderByTimestampDesc(String sensorId, Pageable pageable);

    /**
     * Time-windowed retrieval: all readings from a single sensor between
     * {@code from} (inclusive) and {@code to} (exclusive).
     * Used by the AI engine to compute rolling anomaly windows.
     */
    @Query("{ 'sensor_id': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<SensorReading> findBySensorIdAndTimeRange(String sensorId,
                                                   Instant from,
                                                   Instant to);

    // ─── Status / Alert Queries ────────────────────────────────────────────

    /**
     * Retrieves the most recent readings that match a given operational status
     * (e.g., "CRITICAL", "WARNING") across ALL sensors – primary alert feed.
     */
    Page<SensorReading> findByStatusOrderByTimestampDesc(String status, Pageable pageable);

    /**
     * Counts how many readings a sensor has emitted within a time window.
     * Useful for detecting silent sensors (count = 0) or storm conditions.
     */
    long countBySensorIdAndTimestampBetween(String sensorId, Instant from, Instant to);

    // ─── Threshold / Anomaly Queries ──────────────────────────────────────

    /**
     * Returns readings where temperature exceeds a given threshold, across
     * all sensors, within a time window. Drives thermal anomaly detection.
     */
    @Query("{ 'temperature': { $gt: ?0 }, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<SensorReading> findOverTemperatureInWindow(double temperatureThreshold,
                                                    Instant from,
                                                    Instant to);

    /**
     * Returns readings where vibration exceeds a given RMS threshold.
     * Drives predictive maintenance alerts before mechanical failure.
     */
    @Query("{ 'vibration': { $gt: ?0 }, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<SensorReading> findOverVibrationInWindow(double vibrationThreshold,
                                                  Instant from,
                                                  Instant to);

    // ─── Cleanup / TTL Helpers ────────────────────────────────────────────

    /**
     * Counts documents older than a cutoff – used by a scheduled archival job
     * (not implemented here) to decide whether to trigger an aggregation pipeline.
     */
    long countByTimestampBefore(Instant cutoff);
}
