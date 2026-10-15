package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.orchestration.OrchestrationExceptions.IllegalStateTransitionException;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING, READY",
            "READY, RUNNING",
            "READY, AWAITING_APPROVAL",
            "AWAITING_APPROVAL, READY",
            "RUNNING, SUCCEEDED",
            "RUNNING, RETRYING",
            "RETRYING, RUNNING",
            "RUNNING, FAILED",
            "FAILED, ROLLED_BACK",
            "SUCCEEDED, PENDING",
            "ROLLED_BACK, PENDING",
            "AWAITING_APPROVAL, PENDING",
            "CANCELLED, PENDING"
    })
    void allowsLegalTransitions(TaskStatus from, TaskStatus to) {
        assertThat(RunStateMachine.transition("t", from, to)).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, RUNNING",
            "PENDING, SUCCEEDED",
            "READY, SUCCEEDED",
            "AWAITING_APPROVAL, RUNNING",
            "SUCCEEDED, RUNNING",
            "FAILED, SUCCEEDED",
            "CANCELLED, READY",
            "CANCELLED, RUNNING",
            "ROLLED_BACK, SUCCEEDED"
    })
    void rejectsIllegalTransitions(TaskStatus from, TaskStatus to) {
        assertThatThrownBy(() -> RunStateMachine.transition("t", from, to))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    /** Every state has an explicit entry, and no state may transition to itself. */
    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void noSelfTransitions(TaskStatus status) {
        assertThat(RunStateMachine.canTransition(status, status)).isFalse();
        assertThatThrownBy(() -> RunStateMachine.transition("t", status, status))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void runningMovesOnlyToOutcomeStates() {
        Set<TaskStatus> fromRunning = EnumSet.copyOf(RunStateMachine.allowedFrom(TaskStatus.RUNNING));
        assertThat(fromRunning).containsExactlyInAnyOrder(
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.RETRYING, TaskStatus.CANCELLED);
    }

    @Test
    void cancelledCanOnlyBeRevivedByReplanning() {
        assertThat(RunStateMachine.allowedFrom(TaskStatus.CANCELLED)).containsExactly(TaskStatus.PENDING);
    }

    @Test
    void approvalGateCannotBeSkipped() {
        assertThat(RunStateMachine.canTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.RUNNING)).isFalse();
        assertThat(RunStateMachine.canTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.SUCCEEDED)).isFalse();
    }
}
