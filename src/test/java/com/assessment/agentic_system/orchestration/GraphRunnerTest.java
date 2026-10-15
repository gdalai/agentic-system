package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.agentic_system.agent.AgentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphRunnerTest {

    private final GraphRunner runner = new GraphRunner();
    private final RecordingListener listener = new RecordingListener();

    private static TaskGraph plan() {
        return new WorkflowPlanner().plan(Scenario.GREENFIELD);
    }

    private static AgentResult ok(TaskNode node) {
        return AgentResult.success(node.id() + " done", Map.of("by", node.id()), List.of());
    }

    @Test
    void happyPathRunsEveryTaskInDependencyOrder() {
        TaskGraph graph = plan();

        RunStatus outcome = runner.run(graph, (node, done) -> ok(node), listener);

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(graph.isComplete()).isTrue();
        assertThat(listener.executed).containsExactly(
                "requirements", "architecture", "implementation", "security", "test", "documentation");
        assertThat(listener.transitionsFor("requirements"))
                .containsExactly("PENDING->READY", "READY->RUNNING", "RUNNING->SUCCEEDED");
    }

    @Test
    void eachTaskSeesOutputsOfEveryCompletedUpstreamTask() {
        TaskGraph graph = plan();
        List<Set<String>> seenAtTest = new ArrayList<>();

        runner.run(graph, (node, done) -> {
            if (node.id().equals("test")) {
                seenAtTest.add(done.keySet());
            }
            return ok(node);
        }, listener);

        assertThat(seenAtTest).singleElement().satisfies(keys -> assertThat(keys)
                .containsExactlyInAnyOrder("requirements", "architecture", "implementation", "security"));
    }

    @Test
    void failedTaskBlocksEverythingDownstreamAndFailsTheRun() {
        TaskGraph graph = plan();

        RunStatus outcome = runner.run(graph, (node, done) -> node.id().equals("security")
                ? AgentResult.failure("SCAN_FAILED", "scanner unavailable")
                : ok(node), listener);

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(graph.get("security").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(graph.get("implementation").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(graph.get("test").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(graph.get("documentation").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(listener.executed).doesNotContain("test", "documentation");
    }

    @Test
    void agentExceptionIsContainedAsTaskFailure() {
        TaskGraph graph = plan();

        RunStatus outcome = runner.run(graph, (node, done) -> {
            if (node.id().equals("requirements")) {
                throw new IllegalStateException("boom");
            }
            return ok(node);
        }, listener);

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(listener.results.get(0).errorCode()).isEqualTo("AGENT_EXCEPTION");
        assertThat(graph.get("architecture").status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void nullResultIsTreatedAsFailure() {
        TaskGraph graph = TaskGraph.of(TaskNode.of("only", "X"));

        RunStatus outcome = runner.run(graph, (node, done) -> null, listener);

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(listener.results.get(0).errorCode()).isEqualTo("NULL_RESULT");
    }

    private static final class RecordingListener implements GraphRunner.Listener {
        final List<String> executed = new ArrayList<>();
        final List<String[]> transitions = new ArrayList<>();
        final List<AgentResult> results = new ArrayList<>();

        @Override
        public void onTransition(TaskNode node, TaskStatus from, TaskStatus to, String detail) {
            transitions.add(new String[] {node.id(), from + "->" + to});
        }

        @Override
        public void onResult(TaskNode node, AgentResult result) {
            executed.add(node.id());
            results.add(result);
        }

        List<String> transitionsFor(String taskId) {
            return transitions.stream().filter(t -> t[0].equals(taskId)).map(t -> t[1]).toList();
        }
    }
}
