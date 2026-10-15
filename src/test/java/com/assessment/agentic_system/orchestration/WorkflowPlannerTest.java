package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkflowPlannerTest {

    private final WorkflowPlanner planner = new WorkflowPlanner();

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyScenarioStartsWithRequirementsAndEndsWithDocumentation(Scenario scenario) {
        TaskGraph graph = planner.plan(scenario);

        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("requirements");
        assertThat(graph.topologicalOrder().getLast()).isEqualTo("documentation");
    }

    @Test
    void greenfieldRunsImplementationAndSecurityInParallelBranches() {
        TaskGraph graph = planner.plan(Scenario.GREENFIELD);

        assertThat(graph.size()).isEqualTo(6);
        assertThat(graph.get("implementation").dependsOn()).containsExactly("architecture");
        assertThat(graph.get("security").dependsOn()).containsExactly("architecture");
        assertThat(graph.get("test").dependsOn()).containsExactlyInAnyOrder("implementation", "security");
    }

    @Test
    void brownfieldReproducesTheDefectBeforeFixingIt() {
        TaskGraph graph = planner.plan(Scenario.BROWNFIELD);

        assertThat(graph.size()).isEqualTo(7);
        assertThat(graph.topologicalOrder()).doesNotContain("architecture");
        assertThat(graph.get("impact").dependsOn()).containsExactly("requirements");
        assertThat(graph.get("reproduce").dependsOn()).containsExactly("impact");
        assertThat(graph.get("implementation").dependsOn()).containsExactly("reproduce");
        assertThat(graph.downstreamOf("impact")).contains("reproduce", "implementation", "security", "test");
    }

    @Test
    void ambiguousUsesTheGreenfieldShapeWithTheDecisionLeftToPolicy() {
        assertThat(planner.plan(Scenario.AMBIGUOUS).topologicalOrder())
                .isEqualTo(planner.plan(Scenario.GREENFIELD).topologicalOrder());
    }
}
