package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.orchestration.OrchestrationExceptions.CyclicDependencyException;
import com.assessment.agentic_system.orchestration.OrchestrationExceptions.IllegalStateTransitionException;
import com.assessment.agentic_system.orchestration.OrchestrationExceptions.UnknownDependencyException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskGraphTest {

    /** requirements -> (architecture, security) -> implementation */
    private static TaskGraph diamond() {
        return TaskGraph.of(
                TaskNode.of("implementation", "IMPLEMENTATION", "architecture", "security"),
                TaskNode.of("requirements", "REQUIREMENT"),
                TaskNode.of("architecture", "ARCHITECTURE", "requirements"),
                TaskNode.of("security", "SECURITY", "requirements"));
    }

    private static void succeed(TaskGraph graph, String id) {
        graph.transition(id, TaskStatus.READY);
        graph.transition(id, TaskStatus.RUNNING);
        graph.transition(id, TaskStatus.SUCCEEDED);
    }

    @Test
    void linearChainIsOrderedAndOnlyRootIsReady() {
        TaskGraph graph = TaskGraph.of(
                TaskNode.of("c", "TEST", "b"),
                TaskNode.of("b", "IMPLEMENTATION", "a"),
                TaskNode.of("a", "REQUIREMENT"));

        assertThat(graph.topologicalOrder()).containsExactly("a", "b", "c");
        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("a");
    }

    @Test
    void diamondReleasesBothBranchesInParallelThenJoins() {
        TaskGraph graph = diamond();

        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("requirements");

        succeed(graph, "requirements");
        assertThat(graph.readyTasks()).extracting(TaskNode::id)
                .containsExactlyInAnyOrder("architecture", "security");

        succeed(graph, "architecture");
        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("security");

        succeed(graph, "security");
        assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("implementation");

        succeed(graph, "implementation");
        assertThat(graph.readyTasks()).isEmpty();
        assertThat(graph.isComplete()).isTrue();
    }

    @Test
    void cycleIsRejectedAndNamesInvolvedTasks() {
        assertThatThrownBy(() -> TaskGraph.of(
                TaskNode.of("root", "REQUIREMENT"),
                TaskNode.of("a", "X", "root", "c"),
                TaskNode.of("b", "X", "a"),
                TaskNode.of("c", "X", "b")))
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> assertThat(((CyclicDependencyException) ex).involvedTasks())
                        .containsExactlyInAnyOrder("a", "b", "c"));
    }

    @Test
    void selfDependencyIsRejected() {
        assertThatThrownBy(() -> TaskNode.of("a", "X", "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownDependencyIsRejected() {
        assertThatThrownBy(() -> TaskGraph.of(TaskNode.of("a", "X", "ghost")))
                .isInstanceOf(UnknownDependencyException.class);
    }

    @Test
    void duplicateIdIsRejected() {
        assertThatThrownBy(() -> TaskGraph.of(TaskNode.of("a", "X"), TaskNode.of("a", "Y")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downstreamOfReturnsAllTransitiveDependentsInOrder() {
        TaskGraph graph = diamond();

        assertThat(graph.downstreamOf("requirements"))
                .containsExactlyInAnyOrder("architecture", "security", "implementation");
        List<String> fromRequirements = graph.downstreamOf("requirements");
        assertThat(fromRequirements.getLast()).isEqualTo("implementation");
        assertThat(graph.downstreamOf("architecture")).containsExactly("implementation");
        assertThat(graph.downstreamOf("implementation")).isEmpty();
    }

    @Test
    void failedDependencyKeepsDownstreamBlocked() {
        TaskGraph graph = diamond();
        succeed(graph, "requirements");
        graph.transition("architecture", TaskStatus.READY);
        graph.transition("architecture", TaskStatus.RUNNING);
        graph.transition("architecture", TaskStatus.FAILED);
        succeed(graph, "security");

        assertThat(graph.readyTasks()).isEmpty();
        assertThat(graph.hasFailures()).isTrue();
        assertThat(graph.isComplete()).isFalse();
    }

    @Test
    void illegalTransitionThroughGraphIsRejected() {
        TaskGraph graph = diamond();

        assertThatThrownBy(() -> graph.transition("requirements", TaskStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(graph.get("requirements").status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void attemptsCountEachRunAndResetOnReplan() {
        TaskGraph graph = TaskGraph.of(TaskNode.of("a", "X"));
        graph.transition("a", TaskStatus.READY);
        graph.transition("a", TaskStatus.RUNNING);
        graph.transition("a", TaskStatus.RETRYING);
        graph.transition("a", TaskStatus.RUNNING);
        assertThat(graph.get("a").attempts()).isEqualTo(2);

        graph.transition("a", TaskStatus.SUCCEEDED);
        graph.transition("a", TaskStatus.PENDING);
        assertThat(graph.get("a").attempts()).isZero();
    }
}
