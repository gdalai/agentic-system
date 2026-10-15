package com.assessment.agentic_system.orchestration;

import static com.assessment.agentic_system.orchestration.TaskStatus.AWAITING_APPROVAL;
import static com.assessment.agentic_system.orchestration.TaskStatus.BLOCKED;
import static com.assessment.agentic_system.orchestration.TaskStatus.CANCELLED;
import static com.assessment.agentic_system.orchestration.TaskStatus.FAILED;
import static com.assessment.agentic_system.orchestration.TaskStatus.PENDING;
import static com.assessment.agentic_system.orchestration.TaskStatus.READY;
import static com.assessment.agentic_system.orchestration.TaskStatus.RETRYING;
import static com.assessment.agentic_system.orchestration.TaskStatus.ROLLED_BACK;
import static com.assessment.agentic_system.orchestration.TaskStatus.RUNNING;
import static com.assessment.agentic_system.orchestration.TaskStatus.SUCCEEDED;

import com.assessment.agentic_system.orchestration.OrchestrationExceptions.IllegalStateTransitionException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for legal task status transitions. Every status change in the
 * engine goes through {@link #transition}, so an illegal move fails loudly instead of corrupting state.
 */
public final class RunStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        allow(PENDING, READY, BLOCKED, CANCELLED);
        allow(READY, RUNNING, AWAITING_APPROVAL, CANCELLED);
        allow(AWAITING_APPROVAL, READY, CANCELLED);
        allow(RUNNING, SUCCEEDED, FAILED, RETRYING, CANCELLED);
        allow(RETRYING, RUNNING, FAILED, CANCELLED);
        // Replanning can send finished work back to PENDING; failed work can be rolled back.
        allow(SUCCEEDED, PENDING, ROLLED_BACK);
        allow(FAILED, PENDING, ROLLED_BACK, CANCELLED);
        allow(BLOCKED, PENDING, CANCELLED);
        allow(ROLLED_BACK, PENDING);
        allow(CANCELLED);
    }

    private RunStateMachine() {
    }

    private static void allow(TaskStatus from, TaskStatus... to) {
        Set<TaskStatus> targets = EnumSet.noneOf(TaskStatus.class);
        Collections.addAll(targets, to);
        TRANSITIONS.put(from, Collections.unmodifiableSet(targets));
    }

    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<TaskStatus> allowedFrom(TaskStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    /** Validates and returns the new status, or throws if the move is not allowed. */
    public static TaskStatus transition(String taskId, TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(taskId, from, to);
        }
        return to;
    }
}
