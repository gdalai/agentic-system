package com.assessment.agentic_system.audit;

public enum RunEventType {
    RUN_CREATED,
    RUN_STARTED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_PAUSED,
    RUN_RESUMED,
    RUN_REPLANNED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    TASK_TRANSITION,
    AGENT_RESULT
}
