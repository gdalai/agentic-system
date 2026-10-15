package com.assessment.agentic_system.orchestration;

import static com.assessment.agentic_system.agent.AgentTypes.ARCHITECTURE;
import static com.assessment.agentic_system.agent.AgentTypes.DEFECT_REPRODUCTION;
import static com.assessment.agentic_system.agent.AgentTypes.DOCUMENTATION;
import static com.assessment.agentic_system.agent.AgentTypes.IMPACT_ANALYSIS;
import static com.assessment.agentic_system.agent.AgentTypes.IMPLEMENTATION;
import static com.assessment.agentic_system.agent.AgentTypes.REQUIREMENT;
import static com.assessment.agentic_system.agent.AgentTypes.SECURITY;
import static com.assessment.agentic_system.agent.AgentTypes.TEST;

import org.springframework.stereotype.Component;

/**
 * Turns a scenario into a validated {@link TaskGraph}. All scenarios run on the same engine; they
 * differ only in the plan and in which decisions the policy requires.
 *
 * <pre>
 * GREENFIELD / AMBIGUOUS
 *   requirements -> architecture -> implementation --> test -> documentation
 *                                \-> security -------/
 *
 * BROWNFIELD
 *   requirements -> impact -> reproduce -> implementation --> test -> documentation
 *                          \-> security -----------------/
 * </pre>
 *
 * For AMBIGUOUS, the requirement analysis decides at run time whether the decision gate before
 * architecture is needed; see {@code PolicyEngine}.
 */
@Component
public class WorkflowPlanner {

    public TaskGraph plan(Scenario scenario) {
        return switch (scenario) {
            case GREENFIELD, AMBIGUOUS -> TaskGraph.of(
                    TaskNode.of("requirements", REQUIREMENT),
                    TaskNode.of("architecture", ARCHITECTURE, "requirements"),
                    TaskNode.of("implementation", IMPLEMENTATION, "architecture"),
                    TaskNode.of("security", SECURITY, "architecture"),
                    TaskNode.of("test", TEST, "implementation", "security"),
                    TaskNode.of("documentation", DOCUMENTATION, "test"));
            case BROWNFIELD -> TaskGraph.of(
                    TaskNode.of("requirements", REQUIREMENT),
                    TaskNode.of("impact", IMPACT_ANALYSIS, "requirements"),
                    TaskNode.of("reproduce", DEFECT_REPRODUCTION, "impact"),
                    TaskNode.of("implementation", IMPLEMENTATION, "reproduce"),
                    TaskNode.of("security", SECURITY, "impact"),
                    TaskNode.of("test", TEST, "implementation", "security"),
                    TaskNode.of("documentation", DOCUMENTATION, "test"));
        };
    }
}
