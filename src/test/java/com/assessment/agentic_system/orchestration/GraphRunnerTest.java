package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.agentic_system.agent.AgentResult;
import com.assessment.agentic_system.execution.RetryPolicy;
import com.assessment.agentic_system.orchestration.GraphRunner.ApprovalGate;
import com.assessment.agentic_system.orchestration.GraphRunner.PriorState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphRunnerTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final RecordingListener listener = new RecordingListener();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private GraphRunner runner(RetryPolicy retry) {
        return new GraphRunner(pool, 4, retry);
    }

    private GraphRunner runner() {
        return runner(RetryPolicy.none());
    }

    private static TaskGraph plan() {
        return new WorkflowPlanner().plan(Scenario.GREENFIELD);
    }

    private static AgentResult ok(TaskNode node) {
        return AgentResult.success(node.id() + " done", Map.of("by", node.id()), List.of());
    }

    private RunStatus run(GraphRunner runner, TaskGraph graph, GraphRunner.TaskHandler handler) {
        return runner.run(graph, PriorState.empty(), handler, ApprovalGate.NONE, listener);
    }

    @Test
    void happyPathRunsEveryTaskRespectingDependencies() {
        TaskGraph graph = plan();

        RunStatus outcome = run(runner(), graph, (node, done) -> ok(node));

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(graph.isComplete()).isTrue();
        assertThat(listener.executed).hasSize(6);
        assertThat(listener.executed.getFirst()).isEqualTo("requirements");
        assertThat(listener.executed.indexOf("architecture"))
                .isLessThan(listener.executed.indexOf("implementation"))
                .isLessThan(listener.executed.indexOf("test"));
        assertThat(listener.executed.getLast()).isEqualTo("documentation");
        assertThat(listener.transitionsFor("requirements"))
                .containsExactly("PENDING->READY", "READY->RUNNING", "RUNNING->SUCCEEDED");
    }

    @Test
    void independentTasksRunInParallel() {
        TaskGraph graph = plan();
        // implementation and security each wait for the other to start. Run sequentially, this times out.
        CountDownLatch bothStarted = new CountDownLatch(2);
        Set<String> overlapped = Collections.synchronizedSet(new java.util.HashSet<>());

        RunStatus outcome = run(runner(), graph, (node, done) -> {
            if (node.id().equals("implementation") || node.id().equals("security")) {
                bothStarted.countDown();
                try {
                    if (bothStarted.await(5, TimeUnit.SECONDS)) {
                        overlapped.add(node.id());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ok(node);
        });

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(overlapped).containsExactlyInAnyOrder("implementation", "security");
    }

    @Test
    void parallelismIsCappedAtMaxParallel() {
        TaskGraph graph = TaskGraph.of(
                TaskNode.of("a", "X"), TaskNode.of("b", "X"), TaskNode.of("c", "X"), TaskNode.of("d", "X"));
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        RunStatus outcome = new GraphRunner(pool, 2, RetryPolicy.none()).run(graph, PriorState.empty(),
                (node, done) -> {
                    peak.accumulateAndGet(current.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    current.decrementAndGet();
                    return ok(node);
                }, ApprovalGate.NONE, listener);

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void eachTaskSeesOutputsOfEveryCompletedUpstreamTask() {
        TaskGraph graph = plan();
        List<Set<String>> seenAtTest = Collections.synchronizedList(new ArrayList<>());

        run(runner(), graph, (node, done) -> {
            if (node.id().equals("test")) {
                seenAtTest.add(done.keySet());
            }
            return ok(node);
        });

        assertThat(seenAtTest).singleElement().satisfies(keys -> assertThat(keys)
                .containsExactlyInAnyOrder("requirements", "architecture", "implementation", "security"));
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        TaskGraph graph = plan();
        AtomicInteger securityCalls = new AtomicInteger();

        RunStatus outcome = run(runner(new RetryPolicy(3, 1, 2.0, RetryPolicy.DEFAULT_NON_RETRYABLE)), graph,
                (node, done) -> node.id().equals("security") && securityCalls.incrementAndGet() <= 2
                        ? AgentResult.failure("TIMEOUT", "scanner timed out")
                        : ok(node));

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(graph.get("security").attempts()).isEqualTo(3);
        assertThat(listener.transitionsFor("security")).containsSubsequence(
                "RUNNING->RETRYING", "RETRYING->RUNNING", "RUNNING->RETRYING", "RETRYING->RUNNING",
                "RUNNING->SUCCEEDED");
    }

    @Test
    void retriesAreBoundedAndThenTheRunFailsWithRollback() {
        TaskGraph graph = plan();

        RunStatus outcome = run(runner(new RetryPolicy(2, 1, 2.0, RetryPolicy.DEFAULT_NON_RETRYABLE)), graph,
                (node, done) -> switch (node.id()) {
                    case "security" -> AgentResult.failure("TIMEOUT", "scanner timed out");
                    case "implementation" -> AgentResult.success("staged", Map.of(), List.of("staging/patch.diff"));
                    default -> ok(node);
                });

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(graph.get("security").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(graph.get("security").attempts()).isEqualTo(3);
        // Staged changes from a succeeded task are discarded; tasks without staged changes keep SUCCEEDED.
        assertThat(graph.get("implementation").status()).isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(graph.get("architecture").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(graph.get("test").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(graph.get("documentation").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(listener.executed).doesNotContain("test", "documentation");
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        TaskGraph graph = TaskGraph.of(TaskNode.of("only", "X"));

        RunStatus outcome = run(runner(new RetryPolicy(3, 1, 2.0, Set.of("POLICY_DENIED"))), graph,
                (node, done) -> AgentResult.failure("POLICY_DENIED", "not permitted"));

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(graph.get("only").attempts()).isEqualTo(1);
    }

    @Test
    void agentExceptionIsContainedAsTaskFailure() {
        TaskGraph graph = plan();

        RunStatus outcome = run(runner(), graph, (node, done) -> {
            if (node.id().equals("requirements")) {
                throw new IllegalStateException("boom");
            }
            return ok(node);
        });

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(listener.results.getFirst().errorCode()).isEqualTo("AGENT_EXCEPTION");
        assertThat(graph.get("architecture").status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void approvalGatePausesTheRunAndOtherBranchesContinue() {
        TaskGraph graph = plan();
        ApprovalGate gate = (node, deps) -> node.id().equals("implementation") ? "CODE_MERGE requires approval" : null;

        RunStatus outcome = runner().run(graph, PriorState.empty(), (node, done) -> ok(node), gate, listener);

        assertThat(outcome).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(graph.get("implementation").status()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(graph.get("security").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(graph.get("test").status()).isEqualTo(TaskStatus.PENDING);
        assertThat(listener.executed).doesNotContain("implementation");
    }

    @Test
    void approvedTaskRunsWhenResumedWithoutAskingAgain() {
        TaskGraph graph = plan();
        ApprovalGate gate = (node, deps) -> node.id().equals("implementation") ? "CODE_MERGE requires approval" : null;
        GraphRunner runner = runner();
        runner.run(graph, PriorState.empty(), (node, done) -> ok(node), gate, listener);

        // A human approves: AWAITING_APPROVAL -> READY. Prior outputs are carried into the next segment.
        graph.transition("implementation", TaskStatus.READY);
        Map<String, Map<String, String>> prior = Map.of(
                "requirements", Map.of("by", "requirements"), "architecture", Map.of("by", "architecture"),
                "security", Map.of("by", "security"));
        RunStatus outcome = runner.run(graph, new PriorState(prior, Map.of()), (node, done) -> ok(node), gate, listener);

        assertThat(outcome).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(graph.isComplete()).isTrue();
    }

    @Test
    void rejectedApprovalCancelsTheRunAndBlocksDownstream() {
        TaskGraph graph = plan();
        ApprovalGate gate = (node, deps) -> node.id().equals("implementation") ? "CODE_MERGE requires approval" : null;
        GraphRunner runner = runner();
        runner.run(graph, PriorState.empty(), (node, done) -> ok(node), gate, listener);

        graph.transition("implementation", TaskStatus.CANCELLED);
        RunStatus outcome = runner.run(graph, PriorState.empty(), (node, done) -> ok(node), gate, listener);

        assertThat(outcome).isEqualTo(RunStatus.CANCELLED);
        assertThat(graph.get("test").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(graph.get("documentation").status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void failureElsewhereCancelsPendingApprovals() {
        TaskGraph graph = plan();
        ApprovalGate gate = (node, deps) -> node.id().equals("implementation") ? "CODE_MERGE requires approval" : null;

        RunStatus outcome = runner().run(graph, PriorState.empty(), (node, done) -> node.id().equals("security")
                ? AgentResult.failure("SCAN_FAILED", "scanner unavailable") : ok(node), gate, listener);

        assertThat(outcome).isEqualTo(RunStatus.FAILED);
        assertThat(graph.get("implementation").status()).isEqualTo(TaskStatus.CANCELLED);
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
