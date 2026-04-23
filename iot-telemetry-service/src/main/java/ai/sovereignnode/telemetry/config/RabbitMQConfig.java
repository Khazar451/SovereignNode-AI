package ai.sovereignnode.telemetry.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <h2>RabbitMQConfig – AMQP Topology &amp; Infrastructure Beans</h2>
 *
 * <p>Declares the full RabbitMQ topology required by the telemetry pipeline:
 *
 * <pre>
 *   [REST Controller]
 *        │ publish(sensor.raw)
 *        ▼
 *   ┌──────────────────────┐
 *   │  telemetry.exchange  │  (durable TopicExchange)
 *   └──────────┬───────────┘
 *              │ binding: sensor.#
 *              ▼
 *   ┌──────────────────────┐      x-dead-letter-exchange
 *   │ telemetry.ingest.    │ ──────────────────────────► telemetry.dlx
 *   │      queue           │                                    │
 *   └──────────────────────┘                                    ▼
 *                                                    telemetry.dl.queue
 *                                                  (manual inspection)
 * </pre>
 *
 * <h3>Key Decisions</h3>
 * <ul>
 *   <li><b>Topic exchange</b> – allows future routing keys like
 *       {@code sensor.critical} to bypass normal queues and go directly to
 *       an alert queue without changing producer code.</li>
 *   <li><b>Durable queues</b> – survive broker restarts; essential for
 *       edge deployments where RabbitMQ may be colocated on unreliable hardware.</li>
 *   <li><b>Dead-Letter Exchange (DLX)</b> – poison messages (max retries
 *       exhausted) are routed to {@code telemetry.dl.queue} for human review
 *       rather than silently dropped.</li>
 *   <li><b>Jackson2JsonMessageConverter</b> – messages are serialised as JSON,
 *       making them language-agnostic so Python / Go AI engines can consume them
 *       without a Java deserialiser.</li>
 * </ul>
 */
@Configuration
public class RabbitMQConfig {

    private final BrokerProperties props;

    @Autowired
    public RabbitMQConfig(BrokerProperties props) {
        this.props = props;
    }

    // ─── Exchange Declarations ─────────────────────────────────────────────

    /** Primary durable topic exchange. Survives broker restart. */
    @Bean
    public TopicExchange telemetryExchange() {
        return ExchangeBuilder
                .topicExchange(props.getExchange())
                .durable(true)
                .build();
    }

    /** Dead-letter exchange: receives rejected / TTL-expired messages. */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(props.getDeadLetterExchange())
                .durable(true)
                .build();
    }

    // ─── Queue Declarations ────────────────────────────────────────────────

    /**
     * Primary ingestion queue. Configured with x-dead-letter-exchange so that
     * nack()'d messages are automatically routed to the DLX.
     */
    @Bean
    public Queue telemetryQueue() {
        return QueueBuilder
                .durable(props.getQueue())
                .withArgument("x-dead-letter-exchange", props.getDeadLetterExchange())
                .withArgument("x-message-ttl", 60_000)   // 60 s TTL; tune per AI latency SLA
                .build();
    }

    /** Dead-letter queue for undeliverable messages. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(props.getDeadLetterQueue())
                .build();
    }

    // ─── Bindings ─────────────────────────────────────────────────────────

    /** Bind the ingestion queue to the topic exchange using the configured routing key. */
    @Bean
    public Binding telemetryBinding(Queue telemetryQueue, TopicExchange telemetryExchange) {
        return BindingBuilder
                .bind(telemetryQueue)
                .to(telemetryExchange)
                .with(props.getRoutingKey());
    }

    /** Bind the DL queue to the DL exchange. */
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .withQueueName();   // Route by queue name (direct exchange convention)
    }

    // ─── Message Converter ─────────────────────────────────────────────────

    /**
     * JSON converter. All AMQP messages will be serialised/deserialised as JSON.
     * Language-agnostic: downstream Python AI engines can consume without Java.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ─── RabbitTemplate ────────────────────────────────────────────────────

    /**
     * Configured {@link RabbitTemplate} with:
     * <ul>
     *   <li>JSON message converter.</li>
     *   <li>Mandatory flag – throws if no queue can route the message
     *       (prevents silent message loss on misconfigured binding).</li>
     *   <li>Publisher confirm + return callbacks wired in
     *       {@link ai.sovereignnode.telemetry.messaging.TelemetryProducerService}.</li>
     * </ul>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setMandatory(true);
        return template;
    }

    // ─── Listener Container Factory ────────────────────────────────────────

    /**
     * Listener container factory with manual ACK mode and JSON converter.
     * Consumed by {@code @RabbitListener} methods on any future consumer beans.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        // Manual ACK – prevent message loss if consumer crashes mid-processing
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(10);
        return factory;
    }
}
