package com.assessment.agentic_system.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkflowExecutionConfig {

    /** Bounded pool that runs workflow runs in the background, so POST returns immediately. */
    @Bean(name = "workflowExecutor")
    public ThreadPoolTaskExecutor workflowExecutor(
            @Value("${orchestration.max-concurrent-runs:2}") int maxConcurrentRuns) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentRuns);
        executor.setMaxPoolSize(maxConcurrentRuns);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("workflow-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
