package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.agent.AgentContext;
import com.assessment.agentic_system.agent.AgentRegistry;
import com.assessment.agentic_system.agent.AgentResult;
import com.assessment.agentic_system.audit.AuditService;
import com.assessment.agentic_system.audit.RunEvent;
import com.assessment.agentic_system.audit.RunEventType;
import com.assessment.agentic_system.common.JsonCodec;
import com.assessment.agentic_system.orchestration.persistence.TaskExecution;
import com.assessment.agentic_system.orchestration.persistence.TaskExecutionRepository;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRun;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Starts workflow runs and executes them in the background. Every state change is persisted and
 * audited as it happens, so a run can be inspected through the API while it is executing.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowPlanner planner;
    private final AgentRegistry agents;
    private final WorkflowRunRepository runs;
    private final TaskExecutionRepository tasks;
    private final AuditService audit;
    private final TaskExecutor executor;
    private final Clock clock;
    private final GraphRunner runner = new GraphRunner();

    public WorkflowService(WorkflowPlanner planner, AgentRegistry agents, WorkflowRunRepository runs,
            TaskExecutionRepository tasks, AuditService audit,
            @Qualifier("workflowExecutor") TaskExecutor executor, Clock clock) {
        this.planner = planner;
        this.agents = agents;
        this.runs = runs;
        this.tasks = tasks;
        this.audit = audit;
        this.executor = executor;
        this.clock = clock;
    }

    /** Plans and persists a run, then schedules it. Returns as soon as the run is stored. */
    public WorkflowRun start(Scenario scenario, String requirement, AutonomyLevel autonomy, List<String> approvals) {
        TaskGraph graph = planner.plan(scenario);
        agents.requireAll(graph.nodes().stream().map(TaskNode::agentType).toList());

        WorkflowRun run = runs.save(new WorkflowRun(scenario, requirement, autonomy, approvals, clock.instant()));
        for (String taskId : graph.topologicalOrder()) {
            TaskNode node = graph.get(taskId);
            tasks.save(new TaskExecution(run.getId(), node.id(), node.agentType(), node.dependsOn()));
        }
        audit.runEvent(run.getId(), RunEventType.RUN_CREATED,
                "scenario=" + scenario + ", tasks=" + graph.size() + ", autonomy=" + autonomy);

        UUID runId = run.getId();
        executor.execute(() -> execute(runId, graph));
        return run;
    }

    void execute(UUID runId, TaskGraph graph) {
        WorkflowRun run = getRun(runId);
        try {
            updateRunStatus(run, RunStatus.RUNNING, RunEventType.RUN_STARTED, "execution started");

            RunStatus outcome = runner.run(graph,
                    (node, completed) -> agents.get(node.agentType()).execute(new AgentContext(
                            runId, node.id(), run.getRequirement(), run.getRequirementVersion(), completed)),
                    new PersistingListener(runId));

            if (outcome == RunStatus.SUCCEEDED) {
                updateRunStatus(run, RunStatus.SUCCEEDED, RunEventType.RUN_SUCCEEDED, "all tasks succeeded");
            } else {
                updateRunStatus(run, RunStatus.FAILED, RunEventType.RUN_FAILED, "one or more tasks failed");
            }
        } catch (RuntimeException e) {
            log.error("Workflow run {} aborted", runId, e);
            updateRunStatus(getRun(runId), RunStatus.FAILED, RunEventType.RUN_FAILED,
                    "engine error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public WorkflowRun getRun(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new WorkflowNotFoundException(runId));
    }

    public List<TaskExecution> getTasks(UUID runId) {
        getRun(runId);
        return tasks.findByRunIdOrderByIdAsc(runId);
    }

    public List<RunEvent> getEvents(UUID runId) {
        getRun(runId);
        return audit.eventsFor(runId);
    }

    private void updateRunStatus(WorkflowRun run, RunStatus status, RunEventType event, String detail) {
        run.changeStatus(status, clock.instant());
        runs.save(run);
        audit.runEvent(run.getId(), event, detail);
    }

    /** Writes each transition and result to task_execution and run_event as it happens. */
    private final class PersistingListener implements GraphRunner.Listener {
        private final UUID runId;

        PersistingListener(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void onTransition(TaskNode node, TaskStatus from, TaskStatus to, String detail) {
            TaskExecution task = task(node);
            task.update(to, node.attempts(), clock.instant());
            tasks.save(task);
            audit.taskTransition(runId, node.id(), from, to, detail);
        }

        @Override
        public void onResult(TaskNode node, AgentResult result) {
            TaskExecution task = task(node);
            task.recordOutputs(JsonCodec.encode(result.outputs()),
                    result.succeeded() ? null : result.errorCode() + ": " + result.summary());
            tasks.save(task);
            audit.agentResult(runId, node.id(), (result.succeeded() ? "OK " : "FAILED ") + result.summary()
                    + (result.artifacts().isEmpty() ? "" : " artifacts=" + result.artifacts()));
        }

        private TaskExecution task(TaskNode node) {
            return tasks.findByRunIdAndTaskId(runId, node.id())
                    .orElseThrow(() -> new IllegalStateException("Missing task row " + runId + "/" + node.id()));
        }
    }
}
