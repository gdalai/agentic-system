package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkflowPlannerTest {

    private final WorkflowPlanner planner = new WorkflowPlanner();

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyScenarioProducesAValidGraphStartingWithRequirements(Scenario scenario) {
        TaskGraph graph = planner.plan(scenario);

        assertThat(graph.size()).isEqualTo(6);
        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("requirements");
        assertThat(graph.topologicalOrder().getLast()).isEqualTo("documentation");
    }

    @Test
    void implementationAndSecurityAreIndependentAndJoinAtTest() {
        TaskGraph graph = planner.plan(Scenario.GREENFIELD);

        assertThat(graph.get("implementation").dependsOn()).containsExactly("architecture");
        assertThat(graph.get("security").dependsOn()).containsExactly("architecture");
        assertThat(graph.get("test").dependsOn()).containsExactlyInAnyOrder("implementation", "security");
    }
}
