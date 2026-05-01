package ai.sovereignnode.telemetry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * <h2>IoT Telemetry Ingestion Service – Bootstrap Entry Point</h2>
 *
 * <p>Bootstraps the Spring context for the edge-native data ingestion layer.
 * Key capabilities enabled here:
 * <ul>
 *   <li><b>@EnableAsync</b> – activates the virtual-thread {@link java.util.concurrent.Executor}
 *       defined in {@link ai.sovereignnode.telemetry.config.AsyncConfig} for non-blocking
 *       message dispatch.</li>
 *   <li><b>@EnableMongoAuditing</b> – auto-populates {@code @CreatedDate}/{@code @LastModifiedDate}
 *       on all MongoDB documents.</li>
 *   <li><b>@ConfigurationPropertiesScan</b> – discovers all {@link org.springframework.boot.context.properties.ConfigurationProperties}
 *       beans without requiring explicit {@code @EnableConfigurationProperties}.</li>
 * </ul>
 *
 * <p>Runs on Java 21 virtual threads (configured in {@code application.yml} via
 * {@code spring.threads.virtual.enabled=true}) to achieve extremely high concurrency
 * with minimal OS thread overhead – essential for high-frequency sensor workloads.
 *
 * @author SovereignNode Platform Team
 */
@SpringBootApplication
@EnableAsync
@EnableMongoAuditing
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
@ConfigurationPropertiesScan("ai.sovereignnode.telemetry.config")
public class TelemetryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelemetryServiceApplication.class, args);
    }
}
