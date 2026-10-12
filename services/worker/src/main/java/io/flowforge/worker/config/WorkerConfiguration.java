package io.flowforge.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
public class WorkerConfiguration {

    @Bean
    public Executor workerExecutionExecutor(
            @Value("${flowforge.worker.execution-concurrency:4}") int concurrency,
            @Value("${flowforge.worker.recovery-batch-size:10}") int recoveryBatchSize) {
        if (concurrency < 1 || recoveryBatchSize < 1) {
            throw new IllegalArgumentException("Worker concurrency and recovery batch size must be positive");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(recoveryBatchSize * 2);
        executor.setThreadNamePrefix("flowforge-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
