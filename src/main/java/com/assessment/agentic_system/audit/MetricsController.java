package com.assessment.agentic_system.audit;

import com.assessment.agentic_system.audit.MetricsService.OrchestrationMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    /** Orchestration reliability: outcomes, retries, recoveries, rollbacks, approvals, replans, latency. */
    @GetMapping("/api/v1/metrics")
    public OrchestrationMetrics metrics() {
        return metrics.compute();
    }
}
