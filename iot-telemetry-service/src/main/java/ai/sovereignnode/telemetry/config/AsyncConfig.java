package ai.sovereignnode.telemetry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * <h2>AsyncConfig – Virtual-Thread Executor for @Async Operations</h2>
 *
 * <p>Provides a virtual-thread–backed {@link Executor} as the application's
 * primary {@code @Async} executor, leveraging Java 21 Project Loom.
 *
 * <h3>Why Virtual Threads for IoT Ingestion?</h3>
 * <ul>
 *   <li><b>Low overhead</b>: Each sensor telemetry publish spawns a virtual thread
 *       (heap-allocated, ~1 KB stack) rather than a platform thread (~1 MB stack).
 *       This allows tens of thousands of concurrent sensor sessions.</li>
 *   <li><b>Blocking-safe</b>: Calls to MongoDB and RabbitMQ block the virtual
 *       thread, not the OS thread, so the carrier thread is immediately reused.</li>
 *   <li><b>No Reactive complexity</b>: We avoid Project Reactor entirely – same
 *       concurrency profile without callback pyramids or operator chains.</li>
 * </ul>
 *
 * <p>The {@code threadPoolTaskExecutor} bean is kept for bounded-parallelism
 * scenarios (e.g., batch archival) where you explicitly need to cap concurrency.
 */
@Configuration
public class AsyncConfig {

    /**
     * Primary async executor: unbounded virtual thread pool.
     * Named {@code "taskExecutor"} so Spring picks it up as the default
     * {@code @Async} executor automatically.
     */
    @Bean(name = "taskExecutor")
    public Executor virtualThreadExecutor() {
        // Executors.newVirtualThreadPerTaskExecutor() – Java 21 API
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Secondary executor for tasks that need bounded concurrency
     * (e.g., batch re-processing dead-letter messages).
     * Manually inject with {@code @Async("boundedExecutor")}.
     */
    @Bean(name = "boundedExecutor")
    public AsyncTaskExecutor boundedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("bounded-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
