package dev.aperture.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler configuration.
 *
 * <p>Spring's default {@code TaskScheduler} pool size is <strong>one</strong>. With a two-second
 * market-data tick that can block on a vendor HTTP call, every other scheduled job - history
 * backfill, stream retry, dividend refresh - queues behind it and effectively never runs. Four
 * threads is enough to keep them independent.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("aperture-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
