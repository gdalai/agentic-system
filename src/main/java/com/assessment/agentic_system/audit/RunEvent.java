package com.assessment.agentic_system.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record: every run and task transition, and every agent result. */
@Entity
@Table(name = "run_event")
public class RunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 48)
    private RunEventType eventType;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(length = 1000)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RunEvent() {
        // for JPA
    }

    public RunEvent(UUID runId, String taskId, RunEventType eventType, String fromStatus, String toStatus,
            String detail, Instant occurredAt) {
        this.runId = runId;
        this.taskId = taskId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.detail = detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000);
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getTaskId() { return taskId; }
    public RunEventType getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
