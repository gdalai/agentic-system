package com.assessment.agentic_system.orchestration.persistence;

import com.assessment.agentic_system.orchestration.AutonomyLevel;
import com.assessment.agentic_system.orchestration.RunStatus;
import com.assessment.agentic_system.orchestration.Scenario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private Scenario scenario;

    @Column(nullable = false, length = 4000)
    private String requirement;

    @Column(name = "requirement_version", nullable = false)
    private int requirementVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "autonomy_level", nullable = false, length = 32)
    private AutonomyLevel autonomyLevel;

    @Column(name = "approval_required_for", length = 512)
    private String approvalRequiredFor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowRun() {
        // for JPA
    }

    public WorkflowRun(Scenario scenario, String requirement, AutonomyLevel autonomyLevel,
            List<String> approvalRequiredFor, Instant now) {
        this.id = UUID.randomUUID();
        this.scenario = scenario;
        this.requirement = requirement;
        this.requirementVersion = 1;
        this.autonomyLevel = autonomyLevel;
        this.approvalRequiredFor = approvalRequiredFor == null ? null : String.join(",", approvalRequiredFor);
        this.status = RunStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Records a new requirement version. The engine then invalidates and re-runs impacted tasks. */
    public void replan(String newRequirement, Instant now) {
        this.requirement = newRequirement;
        this.requirementVersion++;
        this.updatedAt = now;
    }

    public void changeStatus(RunStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Scenario getScenario() { return scenario; }
    public String getRequirement() { return requirement; }
    public int getRequirementVersion() { return requirementVersion; }
    public AutonomyLevel getAutonomyLevel() { return autonomyLevel; }
    public RunStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<String> getApprovalRequiredFor() {
        if (approvalRequiredFor == null || approvalRequiredFor.isBlank()) {
            return List.of();
        }
        return Arrays.stream(approvalRequiredFor.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
