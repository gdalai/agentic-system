package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.execution.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Two bounded pools: one runs workflow runner loops, the other runs agents. Keeping them separate
 * means a runner waiting on its agents can never starve the pool those agents need.
 */
@Configuration
public class WorkflowExecutionConfig {

    @Bean(name = "workflowExecutor")
    public ThreadPoolTaskExecutor workflowExecutor(
            @Value("${orchestration.max-concurrent-runs:2}") int maxConcurrentRuns) {
        return pool("workflow-", maxConcurrentRuns);
    }

    @Bean(name = "agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(
            @Value("${orchestration.max-parallel-tasks:4}") int maxParallelTasks,
            @Value("${orchestration.max-concurrent-runs:2}") int maxConcurrentRuns) {
        return pool("agent-", maxParallelTasks * maxConcurrentRuns);
    }

    @Bean
    public RetryPolicy retryPolicy(
            @Value("${orchestration.max-retries:3}") int maxRetries,
            @Value("${orchestration.retry.initial-backoff-ms:200}") long initialBackoffMs,
            @Value("${orchestration.retry.multiplier:2.0}") double multiplier) {
        return new RetryPolicy(maxRetries, initialBackoffMs, multiplier, RetryPolicy.DEFAULT_NON_RETRYABLE);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int size) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
