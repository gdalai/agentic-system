package com.assessment.agentic_system.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.agent.AgentTypes;
import com.assessment.agentic_system.governance.ApproverPolicy.ApprovalNotAuthorizedException;
import com.assessment.agentic_system.orchestration.AutonomyLevel;
import com.assessment.agentic_system.orchestration.Scenario;
import java.util.List;
import java.util.Map;
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
    void ambiguityGateAppliesUnlessAutonomousExceptInTheAmbiguousScenario() {
        Map<String, Map<String, String>> ambiguous = Map.of("requirements",
                Map.of("ambiguityCount", "2", "ambiguities", "'fast' | 'etc'", "assumptions", "p95 < 100 ms"));
        Map<String, Map<String, String>> clear = Map.of("requirements", Map.of("ambiguityCount", "0"));

        assertThat(policy.approvalReason(Scenario.GREENFIELD, AutonomyLevel.CONTROLLED, List.of(), "architecture",
                AgentTypes.ARCHITECTURE, ambiguous)).startsWith("Decision gate").contains("p95 < 100 ms");
        assertThat(policy.approvalReason(Scenario.GREENFIELD, AutonomyLevel.AUTONOMOUS, List.of(), "architecture",
                AgentTypes.ARCHITECTURE, ambiguous)).isNull();
        assertThat(policy.approvalReason(Scenario.AMBIGUOUS, AutonomyLevel.AUTONOMOUS, List.of(), "architecture",
                AgentTypes.ARCHITECTURE, ambiguous)).startsWith("Decision gate");
        assertThat(policy.approvalReason(Scenario.AMBIGUOUS, AutonomyLevel.AUTONOMOUS, List.of(), "architecture",
                AgentTypes.ARCHITECTURE, clear)).isNull();
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
