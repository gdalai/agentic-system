package com.assessment.agentic_system.orchestration;

import static com.assessment.agentic_system.agent.AgentTypes.ARCHITECTURE;
import static com.assessment.agentic_system.agent.AgentTypes.DOCUMENTATION;
import static com.assessment.agentic_system.agent.AgentTypes.IMPLEMENTATION;
import static com.assessment.agentic_system.agent.AgentTypes.REQUIREMENT;
import static com.assessment.agentic_system.agent.AgentTypes.SECURITY;
import static com.assessment.agentic_system.agent.AgentTypes.TEST;

import org.springframework.stereotype.Component;

/**
 * Turns a scenario into a validated {@link TaskGraph}.
 *
 * <pre>
 * requirements -> architecture -> implementation --> test -> documentation
 *                              \-> security -------/
 * </pre>
 *
 * All scenarios currently share this plan. Scenario-specific plans, and LLM-proposed plans,
 * come later; whatever proposes a plan, it is always validated by {@code TaskGraph} construction.
 */
@Component
public class WorkflowPlanner {

    public TaskGraph plan(Scenario scenario) {
        return TaskGraph.of(
                TaskNode.of("requirements", REQUIREMENT),
                TaskNode.of("architecture", ARCHITECTURE, "requirements"),
                TaskNode.of("implementation", IMPLEMENTATION, "architecture"),
                TaskNode.of("security", SECURITY, "architecture"),
                TaskNode.of("test", TEST, "implementation", "security"),
                TaskNode.of("documentation", DOCUMENTATION, "test"));
    }
}
