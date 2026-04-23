package ai.sovereignnode.telemetry.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h2>BrokerProperties – Typesafe RabbitMQ Topology Configuration</h2>
 *
 * <p>Binds all {@code telemetry.broker.*} keys from {@code application.yml}
 * into a strongly-typed bean. This prevents magic-string scatter across AMQP
 * configuration components and makes topology changes a single-file operation.
 *
 * <p>Scanned automatically by {@code @ConfigurationPropertiesScan} on the
 * main application class.
 */
@ConfigurationProperties(prefix = "telemetry.broker")
@Getter
@Setter
public class BrokerProperties {

    /** Name of the durable topic exchange. */
    private String exchange;

    /** Name of the primary durable ingestion queue. */
    private String queue;

    /** AMQP routing key pattern (topic syntax, e.g., {@code sensor.#}). */
    private String routingKey;

    /** Dead-letter exchange for undeliverable / poison messages. */
    private String deadLetterExchange;

    /** Queue bound to the dead-letter exchange for manual inspection. */
    private String deadLetterQueue;
}
