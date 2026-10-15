package com.assessment.agentic_system.audit;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final RunEventRepository events;
    private final Clock clock;

    public AuditService(RunEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void runEvent(UUID runId, RunEventType type, String detail) {
        events.save(new RunEvent(runId, null, type, null, null, detail, clock.instant()));
    }

    public void taskEvent(UUID runId, String taskId, RunEventType type, String detail) {
        events.save(new RunEvent(runId, taskId, type, null, null, detail, clock.instant()));
    }

    public void taskTransition(UUID runId, String taskId, Enum<?> from, Enum<?> to, String detail) {
        events.save(new RunEvent(runId, taskId, RunEventType.TASK_TRANSITION,
                from == null ? null : from.name(), to == null ? null : to.name(), detail, clock.instant()));
    }

    public void agentResult(UUID runId, String taskId, String detail) {
        events.save(new RunEvent(runId, taskId, RunEventType.AGENT_RESULT, null, null, detail, clock.instant()));
    }

    public List<RunEvent> eventsFor(UUID runId) {
        return events.findByRunIdOrderByIdAsc(runId);
    }
}
