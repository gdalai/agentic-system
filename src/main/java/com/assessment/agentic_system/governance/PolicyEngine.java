package com.assessment.agentic_system.governance;

import com.assessment.agentic_system.agent.AgentTypes;
import com.assessment.agentic_system.orchestration.AutonomyLevel;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic governance rules. Decides which tasks need a human approval before they run,
 * based on the run's autonomy level and the actions the requester flagged.
 *
 * <ul>
 *   <li>MANUAL: every task needs approval.</li>
 *   <li>CONTROLLED: tasks whose action is in {@code approvalRequiredFor} need approval.</li>
 *   <li>AUTONOMOUS: no approvals.</li>
 * </ul>
 */
@Component
public class PolicyEngine {

    /** The governed action each agent type performs. Approval requests reference these names. */
    static final Map<String, String> ACTION_BY_AGENT = Map.of(
            AgentTypes.REQUIREMENT, "ANALYSIS",
            AgentTypes.ARCHITECTURE, "DESIGN",
            AgentTypes.IMPLEMENTATION, "CODE_MERGE",
            AgentTypes.SECURITY, "SECURITY_REVIEW",
            AgentTypes.TEST, "TEST_RUN",
            AgentTypes.DOCUMENTATION, "RELEASE");

    public String actionFor(String agentType) {
        return ACTION_BY_AGENT.getOrDefault(agentType, agentType);
    }

    /** @return why approval is needed, or null if the task may run without one */
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
}
