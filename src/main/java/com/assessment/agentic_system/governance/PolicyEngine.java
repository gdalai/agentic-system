package com.assessment.agentic_system.governance;

import com.assessment.agentic_system.agent.AgentTypes;
import com.assessment.agentic_system.orchestration.AutonomyLevel;
import com.assessment.agentic_system.orchestration.Scenario;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic governance rules. Agents only report evidence (for example, how many ambiguities a
 * requirement has); this class decides whether a human must decide before the next task runs.
 *
 * <ol>
 *   <li><b>Ambiguity decision gate.</b> If an upstream task reports unresolved ambiguities, the next
 *       task waits for a human. This always applies in the AMBIGUOUS scenario, and in other scenarios
 *       unless autonomy is AUTONOMOUS. Approving means "proceed on the recorded assumptions";
 *       replanning with a clarified requirement is the alternative.</li>
 *   <li><b>Action gates.</b> MANUAL gates every task; CONTROLLED gates the actions listed in
 *       {@code approvalRequiredFor}; AUTONOMOUS gates none.</li>
 * </ol>
 */
@Component
public class PolicyEngine {

    /** The governed action each agent type performs. Approval requests reference these names. */
    static final Map<String, String> ACTION_BY_AGENT = Map.of(
            AgentTypes.REQUIREMENT, "ANALYSIS",
            AgentTypes.IMPACT_ANALYSIS, "ANALYSIS",
            AgentTypes.ARCHITECTURE, "DESIGN",
            AgentTypes.DEFECT_REPRODUCTION, "TEST_RUN",
            AgentTypes.IMPLEMENTATION, "CODE_MERGE",
            AgentTypes.SECURITY, "SECURITY_REVIEW",
            AgentTypes.TEST, "TEST_RUN",
            AgentTypes.DOCUMENTATION, "RELEASE");

    public String actionFor(String agentType) {
        return ACTION_BY_AGENT.getOrDefault(agentType, agentType);
    }

    /**
     * @param dependencyOutputs outputs of this task's direct dependencies, keyed by task id
     * @return why approval is needed, or null if the task may run without one
     */
    public String approvalReason(Scenario scenario, AutonomyLevel autonomy, Collection<String> approvalRequiredFor,
            String taskId, String agentType, Map<String, Map<String, String>> dependencyOutputs) {
        String clarification = ambiguityGate(scenario, autonomy, dependencyOutputs);
        if (clarification != null) {
            return clarification;
        }
        return approvalReason(autonomy, approvalRequiredFor, taskId, agentType);
    }

    /** Action gates only (no dependency evidence). */
    public String approvalReason(AutonomyLevel autonomy, Collection<String> approvalRequiredFor,
            String taskId, String agentType) {
        String action = actionFor(agentType);
        return switch (autonomy) {
            case MANUAL -> "MANUAL autonomy: every task needs approval (" + action + ")";
            case CONTROLLED -> approvalRequiredFor.contains(action)
                    ? "Action " + action + " requires approval (task '" + taskId + "')"
                    : null;
            case AUTONOMOUS -> null;
        };
    }

    private static String ambiguityGate(Scenario scenario, AutonomyLevel autonomy,
            Map<String, Map<String, String>> dependencyOutputs) {
        boolean gateApplies = scenario == Scenario.AMBIGUOUS || autonomy != AutonomyLevel.AUTONOMOUS;
        if (!gateApplies) {
            return null;
        }
        for (Map.Entry<String, Map<String, String>> dep : dependencyOutputs.entrySet()) {
            int count = parseCount(dep.getValue().get("ambiguityCount"));
            if (count > 0) {
                return "Decision gate: '" + dep.getKey() + "' found " + count + " unresolved ambiguities ["
                        + dep.getValue().getOrDefault("ambiguities", "") + "]. Approve to proceed on assumptions ["
                        + dep.getValue().getOrDefault("assumptions", "")
                        + "], or replan with a clarified requirement.";
            }
        }
        return null;
    }

    private static int parseCount(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
