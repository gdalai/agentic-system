package com.assessment.agentic_system.orchestration;

/** The requested action is not valid for the run's current state (HTTP 409). */
public class WorkflowConflictException extends RuntimeException {

    public WorkflowConflictException(String message) {
        super(message);
    }
}
