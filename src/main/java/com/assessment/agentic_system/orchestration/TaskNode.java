package com.assessment.agentic_system.orchestration;

import java.util.Objects;
import java.util.Set;

/**
 * One unit of work in a {@link TaskGraph}. Identity and dependencies are immutable; status and
 * attempt count change only through the owning graph, which routes them through {@link RunStateMachine}.
 */
public final class TaskNode {

    private final String id;
    private final String agentType;
    private final Set<String> dependsOn;
    private TaskStatus status = TaskStatus.PENDING;
    private int attempts;

    public TaskNode(String id, String agentType, Set<String> dependsOn) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        this.id = id;
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.dependsOn = Set.copyOf(dependsOn == null ? Set.of() : dependsOn);
        if (this.dependsOn.contains(id)) {
            throw new IllegalArgumentException("Task '" + id + "' cannot depend on itself");
        }
    }

    public static TaskNode of(String id, String agentType, String... dependsOn) {
        return new TaskNode(id, agentType, Set.of(dependsOn));
    }

    public String id() { return id; }
    public String agentType() { return agentType; }
    public Set<String> dependsOn() { return dependsOn; }
    public TaskStatus status() { return status; }
    public int attempts() { return attempts; }

    void moveTo(TaskStatus next) {
        this.status = RunStateMachine.transition(id, status, next);
        if (next == TaskStatus.RUNNING) {
            attempts++;
        } else if (next == TaskStatus.PENDING) {
            attempts = 0;
        }
    }

    /** Rebuilds state from persistence without replaying transitions. Only TaskGraph may call this. */
    void restore(TaskStatus persistedStatus, int persistedAttempts) {
        this.status = persistedStatus;
        this.attempts = persistedAttempts;
    }

    @Override
    public String toString() {
        return id + "[" + agentType + ", " + status + "]";
    }
}
