package com.assessment.agentic_system.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.agent.AgentTypes;
import com.assessment.agentic_system.governance.ApproverPolicy.ApprovalNotAuthorizedException;
import com.assessment.agentic_system.orchestration.AutonomyLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final PolicyEngine policy = new PolicyEngine();

    @Test
    void controlledRequiresApprovalOnlyForFlaggedActions() {
        List<String> flagged = List.of("CODE_MERGE", "RELEASE");

        assertThat(policy.approvalReason(AutonomyLevel.CONTROLLED, flagged, "implementation", AgentTypes.IMPLEMENTATION))
                .contains("CODE_MERGE");
        assertThat(policy.approvalReason(AutonomyLevel.CONTROLLED, flagged, "documentation", AgentTypes.DOCUMENTATION))
                .contains("RELEASE");
        assertThat(policy.approvalReason(AutonomyLevel.CONTROLLED, flagged, "security", AgentTypes.SECURITY))
                .isNull();
    }

    @Test
    void manualGatesEverythingAndAutonomousGatesNothing() {
        assertThat(policy.approvalReason(AutonomyLevel.MANUAL, List.of(), "requirements", AgentTypes.REQUIREMENT))
                .isNotNull();
        assertThat(policy.approvalReason(AutonomyLevel.AUTONOMOUS, List.of("CODE_MERGE"), "implementation",
                AgentTypes.IMPLEMENTATION)).isNull();
    }

    @Test
    void onlyConfiguredApproversMayDecide() {
        ApproverPolicy approvers = new ApproverPolicy("tech-lead, release-manager");

        assertThatCode(() -> approvers.requireAuthorized("tech-lead")).doesNotThrowAnyException();
        assertThatThrownBy(() -> approvers.requireAuthorized("intern"))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
        assertThatThrownBy(() -> approvers.requireAuthorized(null))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
    }
}
