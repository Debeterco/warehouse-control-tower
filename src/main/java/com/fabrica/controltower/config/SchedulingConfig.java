package com.fabrica.controltower.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduling infrastructure.
 *
 * <p>The {@link TaskScheduler} is injected into {@code TelemetrySimulatorTask}
 * so the interval can be changed at runtime through
 * {@code POST /api/v1/simulation/parameters} — a {@code @Scheduled} with
 * {@code fixedDelayString} would only read the property at startup.</p>
 */
@Configuration
public class SchedulingConfig {

    /**
     * Single scheduler for telemetry.
     *
     * <p>{@code poolSize} above 1 so scheduling the next cycle does not queue
     * behind the cycle currently running.</p>
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("telemetry-simulator-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        // If the application is shutting down, waiting for the current cycle is pointless.
        scheduler.setAwaitTerminationSeconds(0);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /** Executor for the short I/O tasks triggered by the simulation. */
    @Bean
    public ThreadPoolTaskExecutor simulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("simulation-");
        executor.initialize();
        return executor;
    }
}