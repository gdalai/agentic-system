package com.assessment.agentic_system.orchestration.api;

import com.assessment.agentic_system.audit.RunEvent;
import com.assessment.agentic_system.common.JsonCodec;
import com.assessment.agentic_system.orchestration.ApprovalDecision;
import com.assessment.agentic_system.orchestration.AutonomyLevel;
import com.assessment.agentic_system.orchestration.RunStatus;
import com.assessment.agentic_system.orchestration.Scenario;
import com.assessment.agentic_system.orchestration.TaskStatus;
import com.assessment.agentic_system.orchestration.persistence.TaskExecution;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRun;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record WorkflowRequest(
            @NotNull Scenario scenario,
            @NotBlank @Size(max = 4000) String requirement,
            AutonomyLevel autonomyLevel,
            List<@NotBlank String> approvalRequiredFor) {

        public AutonomyLevel autonomyOrDefault() {
            return autonomyLevel == null ? AutonomyLevel.CONTROLLED : autonomyLevel;
        }
    }

    public record ApprovalRequest(
            @NotBlank String taskId,
            @NotNull ApprovalDecision decision,
            @NotBlank String approver,
            @Size(max = 500) String reason) {
    }

    public record ReplanRequest(
            @NotBlank @Size(max = 4000) String requirement,
            String fromTaskId,
            @Size(max = 500) String reason) {
    }

    public record WorkflowRunView(
            UUID runId,
            Scenario scenario,
            String requirement,
            int requirementVersion,
            AutonomyLevel autonomyLevel,
            List<String> approvalRequiredFor,
            RunStatus status,
            long tasksTotal,
            long tasksSucceeded,
            Instant createdAt,
            Instant updatedAt) {

        static WorkflowRunView from(WorkflowRun run, List<TaskExecution> tasks) {
            long succeeded = tasks.stream().filter(t -> t.getStatus() == TaskStatus.SUCCEEDED).count();
            return new WorkflowRunView(run.getId(), run.getScenario(), run.getRequirement(),
                    run.getRequirementVersion(), run.getAutonomyLevel(), run.getApprovalRequiredFor(),
                    run.getStatus(), tasks.size(), succeeded, run.getCreatedAt(), run.getUpdatedAt());
        }
    }

    public record TaskView(
            String taskId,
            String agentType,
            Set<String> dependsOn,
            TaskStatus status,
            int attempts,
            Map<String, String> outputs,
            List<String> artifacts,
            String error,
            Instant startedAt,
            Instant finishedAt) {

        static TaskView from(TaskExecution t) {
            return new TaskView(t.getTaskId(), t.getAgentType(), t.getDependsOn(), t.getStatus(), t.getAttempts(),
                    JsonCodec.decode(t.getOutputs()), t.getArtifacts(), t.getError(), t.getStartedAt(),
                    t.getFinishedAt());
        }
    }

    public record EventView(
            long sequence,
            String taskId,
            String eventType,
            String fromStatus,
            String toStatus,
            String detail,
            Instant occurredAt) {

        static EventView from(RunEvent e) {
            return new EventView(e.getId(), e.getTaskId(), e.getEventType().name(), e.getFromStatus(),
                    e.getToStatus(), e.getDetail(), e.getOccurredAt());
        }
    }
}
