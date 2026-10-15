package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.agent.AgentResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free execution loop over a {@link TaskGraph}. It owns the order of execution and every
 * status transition; side effects (calling agents, persisting, auditing) are delegated to a
 * {@link TaskHandler} and a {@link Listener}, so the loop is unit-testable without Spring or a database.
 *
 * <p>Current behaviour: sequential, no retries. Parallelism, approvals, retries and rollback are
 * layered onto this loop in later steps.
 */
public final class GraphRunner {

    /** Executes one task. Receives outputs of every task completed so far, keyed by task id. */
    @FunctionalInterface
    public interface TaskHandler {
        AgentResult execute(TaskNode node, Map<String, Map<String, String>> completedOutputs);
    }

    /** Observes the run. Called synchronously, in order. */
    public interface Listener {
        void onTransition(TaskNode node, TaskStatus from, TaskStatus to, String detail);

        void onResult(TaskNode node, AgentResult result);
    }

    public RunStatus run(TaskGraph graph, TaskHandler handler, Listener listener) {
        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();

        var ready = graph.readyTasks();
        while (!ready.isEmpty()) {
            for (TaskNode node : ready) {
                move(graph, node, TaskStatus.READY, "dependencies satisfied", listener);
                move(graph, node, TaskStatus.RUNNING, "attempt " + (node.attempts() + 1), listener);

                AgentResult result = invoke(handler, node, outputs);
                listener.onResult(node, result);

                if (result.succeeded()) {
                    outputs.put(node.id(), result.outputs());
                    move(graph, node, TaskStatus.SUCCEEDED, result.summary(), listener);
                } else {
                    move(graph, node, TaskStatus.FAILED, result.errorCode() + ": " + result.summary(), listener);
                }
            }
            ready = graph.readyTasks();
        }

        if (graph.isComplete()) {
            return RunStatus.SUCCEEDED;
        }
        // Nothing runnable is left: anything still pending sits behind a failure.
        for (TaskNode node : graph.nodes()) {
            if (node.status() == TaskStatus.PENDING) {
                move(graph, node, TaskStatus.BLOCKED, "upstream task failed", listener);
            }
        }
        return RunStatus.FAILED;
    }

    private static AgentResult invoke(TaskHandler handler, TaskNode node, Map<String, Map<String, String>> outputs) {
        try {
            AgentResult result = handler.execute(node, Map.copyOf(outputs));
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
