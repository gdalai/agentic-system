package com.assessment.agentic_system.orchestration;

/** Lifecycle of a whole workflow run. */
public enum RunStatus {
    CREATED,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** Finished for the current plan; may still be replanned. */
    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
