package com.assessment.agentic_system.audit;

import com.assessment.agentic_system.orchestration.RunStatus;
import com.assessment.agentic_system.orchestration.TaskStatus;
import com.assessment.agentic_system.orchestration.persistence.TaskExecution;
import com.assessment.agentic_system.orchestration.persistence.TaskExecutionRepository;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRun;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRunRepository;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

/**
 * Reliability metrics computed from persisted runs, tasks and audit events. Computed on demand,
 * which is fine at assessment scale; a production system would pre-aggregate or export to Prometheus.
 */
@Service
public class MetricsService {

    private final WorkflowRunRepository runs;
    private final TaskExecutionRepository tasks;
    private final RunEventRepository events;

    public MetricsService(WorkflowRunRepository runs, TaskExecutionRepository tasks, RunEventRepository events) {
        this.runs = runs;
        this.tasks = tasks;
        this.events = events;
    }

    public record OrchestrationMetrics(
            long runsTotal,
            Map<RunStatus, Long> runsByStatus,
            Double runSuccessRate,
            long agentExecutions,
            long retries,
            long tasksRecoveredByRetry,
            long tasksFailed,
            long rollbacks,
            long approvalsGranted,
            long approvalsRejected,
            long replans,
            Double avgTaskDurationMs,
            Double avgRunDurationMs) {
    }

    public OrchestrationMetrics compute() {
        List<WorkflowRun> allRuns = runs.findAll();
        List<TaskExecution> allTasks = tasks.findAll();

        Map<RunStatus, Long> byStatus = new EnumMap<>(RunStatus.class);
        for (RunStatus s : RunStatus.values()) {
            byStatus.put(s, 0L);
        }
        allRuns.forEach(r -> byStatus.merge(r.getStatus(), 1L, Long::sum));

        long finished = allRuns.stream().filter(r -> r.getStatus().isFinished()).count();
        Double successRate = finished == 0 ? null
                : round(100.0 * byStatus.get(RunStatus.SUCCEEDED) / finished);

        OptionalDouble taskDuration = allTasks.stream()
                .filter(t -> t.getStartedAt() != null && t.getFinishedAt() != null)
                .mapToLong(t -> Duration.between(t.getStartedAt(), t.getFinishedAt()).toMillis())
                .average();
        OptionalDouble runDuration = allRuns.stream()
                .filter(r -> r.getStatus().isFinished())
                .mapToLong(r -> Duration.between(r.getCreatedAt(), r.getUpdatedAt()).toMillis())
                .average();

        return new OrchestrationMetrics(
                allRuns.size(),
                byStatus,
                successRate,
                events.countByEventType(RunEventType.AGENT_RESULT),
                events.countByEventTypeAndToStatus(RunEventType.TASK_TRANSITION, TaskStatus.RETRYING.name()),
                allTasks.stream().filter(t -> t.getStatus() == TaskStatus.SUCCEEDED && t.getAttempts() > 1).count(),
                events.countByEventTypeAndToStatus(RunEventType.TASK_TRANSITION, TaskStatus.FAILED.name()),
                events.countByEventTypeAndToStatus(RunEventType.TASK_TRANSITION, TaskStatus.ROLLED_BACK.name()),
                events.countByEventType(RunEventType.APPROVAL_GRANTED),
                events.countByEventType(RunEventType.APPROVAL_REJECTED),
                events.countByEventType(RunEventType.RUN_REPLANNED),
                taskDuration.isPresent() ? round(taskDuration.getAsDouble()) : null,
                runDuration.isPresent() ? round(runDuration.getAsDouble()) : null);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
