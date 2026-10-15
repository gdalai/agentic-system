package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.agent.AgentResult;
import com.assessment.agentic_system.execution.RetryPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;

/**
 * Framework-free execution loop over a {@link TaskGraph}.
 *
 * <ul>
 *   <li><b>Parallelism:</b> independent ready tasks run concurrently on a worker pool, capped at
 *       {@code maxParallel} in flight. As soon as any task finishes, newly unblocked tasks are dispatched.</li>
 *   <li><b>Single writer:</b> only the thread calling {@link #run} mutates the graph or calls the
 *       listener. Workers only execute agents, so no graph state is shared across threads.</li>
 *   <li><b>Approval gates:</b> a task the {@link ApprovalGate} flags is parked in AWAITING_APPROVAL. When
 *       nothing else can progress, the run returns {@link RunStatus#AWAITING_APPROVAL} so the caller
 *       can persist and resume later.</li>
 *   <li><b>Retries:</b> retryable failures are re-dispatched with exponential backoff per {@link RetryPolicy}.</li>
 *   <li><b>Rollback:</b> if the run ends unsuccessfully, succeeded tasks that produced staged changes
 *       are rolled back, tasks behind a failure are BLOCKED, and pending approvals are CANCELLED.</li>
 * </ul>
 */
public final class GraphRunner {

    /** Artifacts under this prefix are staged changes that must be discarded if the run fails. */
    public static final String STAGED_ARTIFACT_PREFIX = "staging/";

    /** Executes one task. Receives outputs of every task completed so far, keyed by task id. */
    @FunctionalInterface
    public interface TaskHandler {
        AgentResult execute(TaskNode node, Map<String, Map<String, String>> completedOutputs);
    }

    /** Decides whether a task needs a human decision before it runs. Returns the reason, or null. */
    @FunctionalInterface
    public interface ApprovalGate {
        ApprovalGate NONE = node -> null;

        String approvalReason(TaskNode node);
    }

    /** Observes the run. Always called on the runner thread, in order. */
    public interface Listener {
        void onTransition(TaskNode node, TaskStatus from, TaskStatus to, String detail);

        void onResult(TaskNode node, AgentResult result);
    }

    /** State carried over from earlier segments of the same run (after a pause or a replan). */
    public record PriorState(Map<String, Map<String, String>> outputs, Map<String, List<String>> artifacts) {
        public static PriorState empty() {
            return new PriorState(Map.of(), Map.of());
        }
    }

    private record Completed(TaskNode node, AgentResult result) {
    }

    private final Executor workers;
    private final int maxParallel;
    private final RetryPolicy retryPolicy;

    public GraphRunner(Executor workers, int maxParallel, RetryPolicy retryPolicy) {
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel must be >= 1");
        }
        this.workers = workers;
        this.maxParallel = maxParallel;
        this.retryPolicy = retryPolicy;
    }

    public RunStatus run(TaskGraph graph, PriorState prior, TaskHandler handler, ApprovalGate gate, Listener listener) {
        Map<String, Map<String, String>> outputs = new LinkedHashMap<>(prior.outputs());
        Map<String, List<String>> artifacts = new LinkedHashMap<>(prior.artifacts());
        CompletionService<Completed> completions = new ExecutorCompletionService<>(workers);
        int inFlight = 0;

        while (true) {
            // 1. Promote tasks whose dependencies are met; park those that need approval.
            for (TaskNode node : graph.readyTasks()) {
                move(graph, node, TaskStatus.READY, "dependencies satisfied", listener);
                String reason = gate.approvalReason(node);
                if (reason != null) {
                    move(graph, node, TaskStatus.AWAITING_APPROVAL, reason, listener);
                }
            }

            // 2. Dispatch ready tasks up to the parallelism cap.
            for (TaskNode node : graph.tasksIn(TaskStatus.READY)) {
                if (inFlight >= maxParallel) {
                    break;
                }
                move(graph, node, TaskStatus.RUNNING, "attempt " + (node.attempts() + 1), listener);
                submit(completions, handler, node, outputs, 0);
                inFlight++;
            }

            if (inFlight == 0) {
                break;
            }

            // 3. Wait for the next task to finish and apply its result.
            Completed done = takeNext(completions);
            inFlight--;
            TaskNode node = done.node();
            AgentResult result = done.result();
            listener.onResult(node, result);

            if (result.succeeded()) {
                outputs.put(node.id(), result.outputs());
                artifacts.put(node.id(), result.artifacts());
                move(graph, node, TaskStatus.SUCCEEDED, result.summary(), listener);
            } else if (retryPolicy.shouldRetry(result.errorCode(), node.attempts())) {
                long backoff = retryPolicy.backoffMs(node.attempts());
                move(graph, node, TaskStatus.RETRYING,
                        result.errorCode() + ": " + result.summary() + "; retrying in " + backoff + "ms", listener);
                move(graph, node, TaskStatus.RUNNING,
                        "attempt " + (node.attempts() + 1) + " of " + retryPolicy.maxAttempts(), listener);
                submit(completions, handler, node, outputs, backoff);
                inFlight++;
            } else {
                String why = node.attempts() > 1 ? " (after " + node.attempts() + " attempts)" : "";
                move(graph, node, TaskStatus.FAILED, result.errorCode() + ": " + result.summary() + why, listener);
            }
        }

        return finish(graph, artifacts, listener);
    }

    private RunStatus finish(TaskGraph graph, Map<String, List<String>> artifacts, Listener listener) {
        if (graph.isComplete()) {
            return RunStatus.SUCCEEDED;
        }
        boolean failed = graph.hasFailures();
        if (!failed && graph.any(TaskStatus.AWAITING_APPROVAL)) {
            return RunStatus.AWAITING_APPROVAL;
        }

        String cause = failed ? "run failed" : "run cancelled";
        for (TaskNode node : graph.tasksIn(TaskStatus.AWAITING_APPROVAL)) {
            move(graph, node, TaskStatus.CANCELLED, cause + " before approval", listener);
        }
        for (TaskNode node : graph.tasksIn(TaskStatus.PENDING)) {
            move(graph, node, TaskStatus.BLOCKED, "upstream task did not complete", listener);
        }
        for (TaskNode node : graph.tasksIn(TaskStatus.SUCCEEDED)) {
            List<String> staged = artifacts.getOrDefault(node.id(), List.of()).stream()
                    .filter(a -> a.startsWith(STAGED_ARTIFACT_PREFIX)).toList();
            if (!staged.isEmpty()) {
                move(graph, node, TaskStatus.ROLLED_BACK, cause + "; discarded staged changes " + staged, listener);
            }
        }
        return failed ? RunStatus.FAILED : RunStatus.CANCELLED;
    }

    private void submit(CompletionService<Completed> completions, TaskHandler handler, TaskNode node,
            Map<String, Map<String, String>> outputs, long backoffMs) {
        Map<String, Map<String, String>> snapshot = Map.copyOf(outputs);
        completions.submit(() -> {
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Completed(node, AgentResult.failure("INTERRUPTED", "interrupted during backoff"));
                }
            }
            return new Completed(node, invoke(handler, node, snapshot));
        });
    }

    private static Completed takeNext(CompletionService<Completed> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runner interrupted", e);
        } catch (ExecutionException e) {
            // invoke() catches agent exceptions, so this only happens on an engine bug.
            throw new IllegalStateException("Task execution failed unexpectedly", e.getCause());
        }
    }

    private static AgentResult invoke(TaskHandler handler, TaskNode node, Map<String, Map<String, String>> outputs) {
        try {
            AgentResult result = handler.execute(node, outputs);
            return result != null ? result : AgentResult.failure("NULL_RESULT", "Agent returned no result");
        } catch (RuntimeException e) {
            return AgentResult.failure("AGENT_EXCEPTION", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void move(TaskGraph graph, TaskNode node, TaskStatus next, String detail, Listener listener) {
        TaskStatus from = node.status();
        graph.transition(node.id(), next);
        listener.onTransition(node, from, next, detail);
    }
}
