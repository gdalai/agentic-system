package com.assessment.agentic_system.orchestration;

import java.util.UUID;

public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(UUID runId) {
        super("No workflow run found with id " + runId);
    }
}
