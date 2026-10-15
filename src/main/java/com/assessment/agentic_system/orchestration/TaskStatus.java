package com.assessment.agentic_system.orchestration;

/** Lifecycle of a single task in a workflow run. Legal moves are defined in {@link RunStateMachine}. */
public enum TaskStatus {
    PENDING,
    READY,
    RUNNING,
    RETRYING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
    ROLLED_BACK
}
