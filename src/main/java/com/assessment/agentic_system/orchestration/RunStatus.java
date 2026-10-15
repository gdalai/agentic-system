package com.assessment.agentic_system.orchestration;

/** Lifecycle of a whole workflow run. */
public enum RunStatus {
    CREATED,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
