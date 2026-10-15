package com.assessment.agentic_system.orchestration.persistence;

import com.assessment.agentic_system.orchestration.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persisted state of one task within one workflow run. */
@Entity
@Table(name = "task_execution")
public class TaskExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "agent_type", nullable = false, length = 64)
    private String agentType;

    @Column(name = "depends_on", length = 1024)
    private String dependsOn;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 8000)
    private String outputs;

    @Column(length = 1000)
    private String error;

    @Column(length = 2000)
    private String artifacts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected TaskExecution() {
        // for JPA
    }

    public TaskExecution(UUID runId, String taskId, String agentType, Set<String> dependsOn) {
        this.runId = runId;
        this.taskId = taskId;
        this.agentType = agentType;
        this.dependsOn = new TreeSet<>(dependsOn).stream().collect(Collectors.joining(","));
        this.status = TaskStatus.PENDING;
    }

    public void update(TaskStatus next, int attempts, Instant now) {
        this.status = next;
        this.attempts = attempts;
        if (next == TaskStatus.RUNNING && startedAt == null) {
            startedAt = now;
        }
        if (next == TaskStatus.PENDING || next == TaskStatus.READY || next == TaskStatus.RUNNING
                || next == TaskStatus.RETRYING || next == TaskStatus.AWAITING_APPROVAL) {
            finishedAt = null;
        }
        if (next == TaskStatus.SUCCEEDED || next == TaskStatus.FAILED || next == TaskStatus.BLOCKED
                || next == TaskStatus.CANCELLED || next == TaskStatus.ROLLED_BACK) {
            finishedAt = now;
        }
    }

    public void recordResult(String outputsJson, List<String> producedArtifacts, String error) {
        this.outputs = outputsJson;
        String joined = producedArtifacts == null || producedArtifacts.isEmpty() ? null : String.join(",", producedArtifacts);
        this.artifacts = joined == null || joined.length() <= 2000 ? joined : joined.substring(0, 2000);
        this.error = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    /** Back to a clean PENDING state for a replan. The transition itself is validated by the graph. */
    public void resetForReplan() {
        this.status = TaskStatus.PENDING;
        this.attempts = 0;
        this.outputs = null;
        this.artifacts = null;
        this.error = null;
        this.startedAt = null;
        this.finishedAt = null;
    }

    public List<String> getArtifacts() {
        if (artifacts == null || artifacts.isBlank()) {
            return List.of();
        }
        return List.of(artifacts.split(","));
    }

    public Long getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getTaskId() { return taskId; }
    public String getAgentType() { return agentType; }
    public TaskStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getOutputs() { return outputs; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public Set<String> getDependsOn() {
        if (dependsOn == null || dependsOn.isBlank()) {
            return Set.of();
        }
        return new TreeSet<>(Arrays.asList(dependsOn.split(",")));
    }
}
