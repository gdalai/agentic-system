package com.assessment.agentic_system.orchestration;

import java.util.List;

public final class OrchestrationExceptions {

    private OrchestrationExceptions() {
    }

    /** The task graph contains a dependency cycle and can never complete. */
    public static class CyclicDependencyException extends RuntimeException {
        private final List<String> involvedTasks;

        public CyclicDependencyException(List<String> involvedTasks) {
            super("Task graph contains a cycle involving: " + involvedTasks);
            this.involvedTasks = List.copyOf(involvedTasks);
        }

        public List<String> involvedTasks() {
            return involvedTasks;
        }
    }

    /** A task depends on a task id that is not in the graph. */
    public static class UnknownDependencyException extends RuntimeException {
        public UnknownDependencyException(String taskId, String missing) {
            super("Task '" + taskId + "' depends on unknown task '" + missing + "'");
        }
    }

    /** A task id was referenced that does not exist in the graph. */
    public static class UnknownTaskException extends RuntimeException {
        public UnknownTaskException(String taskId) {
            super("Unknown task '" + taskId + "'");
        }
    }

    /** A status change was requested that the state machine does not allow. */
    public static class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String taskId, TaskStatus from, TaskStatus to) {
            super("Task '" + taskId + "' cannot move from " + from + " to " + to);
        }
    }
}
