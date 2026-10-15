package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.orchestration.OrchestrationExceptions.CyclicDependencyException;
import com.assessment.agentic_system.orchestration.OrchestrationExceptions.UnknownDependencyException;
import com.assessment.agentic_system.orchestration.OrchestrationExceptions.UnknownTaskException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A directed acyclic graph of tasks. Construction validates the graph (unknown dependencies and
 * cycles are rejected), so any {@code TaskGraph} instance is always runnable.
 */
public final class TaskGraph {

    private final Map<String, TaskNode> nodes;
    private final Map<String, Set<String>> dependents;
    private final List<String> topologicalOrder;

    public TaskGraph(Collection<TaskNode> tasks) {
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode task : tasks) {
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("Duplicate task id '" + task.id() + "'");
            }
        }
        this.nodes = byId;
        this.dependents = buildDependents(byId);
        this.topologicalOrder = topologicalSort(byId, dependents);
    }

    public static TaskGraph of(TaskNode... tasks) {
        return new TaskGraph(List.of(tasks));
    }

    private static Map<String, Set<String>> buildDependents(Map<String, TaskNode> byId) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        byId.keySet().forEach(id -> result.put(id, new LinkedHashSet<>()));
        for (TaskNode node : byId.values()) {
            for (String dep : node.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new UnknownDependencyException(node.id(), dep);
                }
                result.get(dep).add(node.id());
            }
        }
        return result;
    }

    /** Kahn's algorithm. Any node left unprocessed is part of (or blocked behind) a cycle. */
    private static List<String> topologicalSort(Map<String, TaskNode> byId, Map<String, Set<String>> dependents) {
        Map<String, Integer> inDegree = new HashMap<>();
        byId.values().forEach(n -> inDegree.put(n.id(), n.dependsOn().size()));

        Deque<String> queue = new ArrayDeque<>();
        byId.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(queue::add);

        List<String> order = new ArrayList<>(byId.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String child : dependents.get(id)) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }

        if (order.size() != byId.size()) {
            List<String> stuck = byId.keySet().stream().filter(id -> !order.contains(id)).toList();
            throw new CyclicDependencyException(stuck);
        }
        return List.copyOf(order);
    }

    /** Tasks that can start now: PENDING, with every dependency SUCCEEDED. */
    public List<TaskNode> readyTasks() {
        return topologicalOrder.stream()
                .map(nodes::get)
                .filter(n -> n.status() == TaskStatus.PENDING)
                .filter(n -> n.dependsOn().stream().allMatch(d -> nodes.get(d).status() == TaskStatus.SUCCEEDED))
                .toList();
    }

    /** Every task reachable downstream of {@code taskId} (not including it), in topological order. */
    public List<String> downstreamOf(String taskId) {
        require(taskId);
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(dependents.get(taskId));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (seen.add(id)) {
                stack.addAll(dependents.get(id));
            }
        }
        return topologicalOrder.stream().filter(seen::contains).toList();
    }

    /** Applies a validated status change. Throws if the transition is illegal. */
    public void transition(String taskId, TaskStatus next) {
        require(taskId).moveTo(next);
    }

    public boolean isComplete() {
        return nodes.values().stream().allMatch(n -> n.status() == TaskStatus.SUCCEEDED);
    }

    public boolean hasFailures() {
        return nodes.values().stream().anyMatch(n -> n.status() == TaskStatus.FAILED);
    }

    public TaskNode get(String taskId) {
        return require(taskId);
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    public Collection<TaskNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public int size() {
        return nodes.size();
    }

    private TaskNode require(String taskId) {
        TaskNode node = nodes.get(taskId);
        if (node == null) {
            throw new UnknownTaskException(taskId);
        }
        return node;
    }
}
