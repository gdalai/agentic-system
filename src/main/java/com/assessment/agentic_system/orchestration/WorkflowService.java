package com.assessment.agentic_system.orchestration;

import com.assessment.agentic_system.agent.AgentContext;
import com.assessment.agentic_system.agent.AgentRegistry;
import com.assessment.agentic_system.agent.AgentResult;
import com.assessment.agentic_system.audit.AuditService;
import com.assessment.agentic_system.audit.RunEvent;
import com.assessment.agentic_system.audit.RunEventType;
import com.assessment.agentic_system.common.JsonCodec;
import com.assessment.agentic_system.execution.RetryPolicy;
import com.assessment.agentic_system.governance.ApproverPolicy;
import com.assessment.agentic_system.governance.PolicyEngine;
import com.assessment.agentic_system.orchestration.GraphRunner.PriorState;
import com.assessment.agentic_system.orchestration.persistence.TaskExecution;
import com.assessment.agentic_system.orchestration.persistence.TaskExecutionRepository;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRun;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Runs workflows and applies human decisions to them.
 *
 * <p>Concurrency model: a run is executed by one runner at a time. Approvals and replans are only
 * accepted while no runner is active (run status AWAITING_APPROVAL or finished), under a per-run
 * lock. They update the database and start a fresh runner, which rebuilds the graph from persisted
 * state. Paused runs therefore survive an application restart.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowPlanner planner;
    private final AgentRegistry agents;
    private final PolicyEngine policy;
    private final ApproverPolicy approvers;
    private final WorkflowRunRepository runs;
    private final TaskExecutionRepository tasks;
    private final AuditService audit;
    private final TaskExecutor runExecutor;
    private final GraphRunner runner;
    private final Clock clock;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowPlanner planner, AgentRegistry agents, PolicyEngine policy, ApproverPolicy approvers,
            WorkflowRunRepository runs, TaskExecutionRepository tasks, AuditService audit,
            @Qualifier("workflowExecutor") TaskExecutor runExecutor,
            @Qualifier("agentExecutor") TaskExecutor agentExecutor,
            RetryPolicy retryPolicy,
            @Value("${orchestration.max-parallel-tasks:4}") int maxParallelTasks,
            Clock clock) {
        this.planner = planner;
        this.agents = agents;
        this.policy = policy;
        this.approvers = approvers;
        this.runs = runs;
        this.tasks = tasks;
        this.audit = audit;
        this.runExecutor = runExecutor;
        this.runner = new GraphRunner(agentExecutor, maxParallelTasks, retryPolicy);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ start

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
                "scenario=" + scenario + ", tasks=" + graph.size() + ", autonomy=" + autonomy
                        + ", approvalRequiredFor=" + run.getApprovalRequiredFor());

        schedule(run.getId(), RunEventType.RUN_STARTED, "execution started");
        return run;
    }

    // ------------------------------------------------------------------ approvals

    /** Applies a human decision to a task parked at an approval gate, then resumes the run. */
    public WorkflowRun decide(UUID runId, String taskId, ApprovalDecision decision, String approver, String reason) {
        approvers.requireAuthorized(approver);
        synchronized (lockFor(runId)) {
            WorkflowRun run = getRun(runId);
            if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
                throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus()
                        + "; approvals are accepted only while it is AWAITING_APPROVAL");
            }
            TaskExecution task = tasks.findByRunIdAndTaskId(runId, taskId)
                    .orElseThrow(() -> new WorkflowConflictException("Run " + runId + " has no task '" + taskId + "'"));
            if (task.getStatus() != TaskStatus.AWAITING_APPROVAL) {
                throw new WorkflowConflictException("Task '" + taskId + "' is " + task.getStatus()
                        + ", not AWAITING_APPROVAL");
            }

            TaskStatus next = decision == ApprovalDecision.APPROVE ? TaskStatus.READY : TaskStatus.CANCELLED;
            RunStateMachine.transition(taskId, task.getStatus(), next);
            String detail = approver + ": " + (reason == null || reason.isBlank() ? "(no reason given)" : reason);
            task.update(next, task.getAttempts(), clock.instant());
            tasks.save(task);
            audit.taskEvent(runId, taskId, decision == ApprovalDecision.APPROVE
                    ? RunEventType.APPROVAL_GRANTED : RunEventType.APPROVAL_REJECTED, detail);
            audit.taskTransition(runId, taskId, TaskStatus.AWAITING_APPROVAL, next, decision + " by " + detail);

            return schedule(runId, RunEventType.RUN_RESUMED, decision + " of '" + taskId + "' by " + approver);
        }
    }

    // ------------------------------------------------------------------ replanning

    /**
     * Records a new requirement version and re-runs only what it invalidates: the task named in
     * {@code fromTaskId} (default: the first task) and everything downstream of it, plus any task that
     * did not complete. Tasks upstream of the change keep their results.
     */
    public WorkflowRun replan(UUID runId, String newRequirement, String fromTaskId, String reason) {
        synchronized (lockFor(runId)) {
            WorkflowRun run = getRun(runId);
            if (run.getStatus() != RunStatus.AWAITING_APPROVAL && !run.getStatus().isFinished()) {
                throw new WorkflowConflictException("Run " + runId + " is " + run.getStatus()
                        + "; replanning is allowed when it is paused or finished");
            }
            List<TaskExecution> rows = tasks.findByRunIdOrderByIdAsc(runId);
            TaskGraph graph = rebuildGraph(rows);
            String from = fromTaskId == null || fromTaskId.isBlank() ? graph.topologicalOrder().getFirst() : fromTaskId;
            if (rows.stream().noneMatch(t -> t.getTaskId().equals(from))) {
                throw new WorkflowConflictException("Run " + runId + " has no task '" + from + "'");
            }

            Set<String> invalidated = new LinkedHashSet<>();
            invalidated.add(from);
            invalidated.addAll(graph.downstreamOf(from));
            rows.stream().filter(t -> t.getStatus() != TaskStatus.SUCCEEDED).map(TaskExecution::getTaskId)
                    .forEach(invalidated::add);
            invalidated.removeIf(id -> graph.get(id).status() == TaskStatus.PENDING);

            // Validate every reset through the state machine before touching the database.
            for (String id : invalidated) {
                graph.transition(id, TaskStatus.PENDING);
            }

            int previousVersion = run.getRequirementVersion();
            run.replan(newRequirement, clock.instant());
            runs.save(run);
            audit.runEvent(runId, RunEventType.RUN_REPLANNED, "requirement v" + previousVersion + " -> v"
                    + run.getRequirementVersion() + " from '" + from + "'; invalidated " + invalidated
                    + (reason == null || reason.isBlank() ? "" : "; reason: " + reason));
            for (TaskExecution row : rows) {
                if (invalidated.contains(row.getTaskId())) {
                    TaskStatus previous = row.getStatus();
                    row.resetForReplan();
                    tasks.save(row);
                    audit.taskTransition(runId, row.getTaskId(), previous, TaskStatus.PENDING,
                            "invalidated by requirement v" + run.getRequirementVersion());
                }
            }
            return schedule(runId, RunEventType.RUN_RESUMED, "replanned to requirement v" + run.getRequirementVersion());
        }
    }

    // ------------------------------------------------------------------ queries

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

    // ------------------------------------------------------------------ execution

    /** Marks the run RUNNING and hands it to a runner thread. Caller holds the run lock where relevant. */
    private WorkflowRun schedule(UUID runId, RunEventType event, String detail) {
        WorkflowRun run = getRun(runId);
        run.changeStatus(RunStatus.RUNNING, clock.instant());
        WorkflowRun saved = runs.save(run);
        audit.runEvent(runId, event, detail);
        runExecutor.execute(() -> execute(runId));
        return saved;
    }

    void execute(UUID runId) {
        try {
            WorkflowRun run = getRun(runId);
            List<TaskExecution> rows = tasks.findByRunIdOrderByIdAsc(runId);
            TaskGraph graph = rebuildGraph(rows);
            PriorState prior = priorState(rows);

            RunStatus outcome = runner.run(graph, prior,
                    (node, completed) -> agents.get(node.agentType()).execute(new AgentContext(
                            runId, node.id(), run.getRequirement(), run.getRequirementVersion(), completed)),
                    node -> policy.approvalReason(run.getAutonomyLevel(), run.getApprovalRequiredFor(),
                            node.id(), node.agentType()),
                    new PersistingListener(runId));

            switch (outcome) {
                case SUCCEEDED -> finish(runId, outcome, RunEventType.RUN_SUCCEEDED, "all tasks succeeded");
                case AWAITING_APPROVAL -> finish(runId, outcome, RunEventType.RUN_PAUSED,
                        "waiting for approval of " + graph.tasksIn(TaskStatus.AWAITING_APPROVAL).stream()
                                .map(TaskNode::id).toList());
                case CANCELLED -> finish(runId, outcome, RunEventType.RUN_CANCELLED, "approval rejected");
                default -> finish(runId, RunStatus.FAILED, RunEventType.RUN_FAILED, "one or more tasks failed");
            }
        } catch (RuntimeException e) {
            log.error("Workflow run {} aborted", runId, e);
            finish(runId, RunStatus.FAILED, RunEventType.RUN_FAILED,
                    "engine error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void finish(UUID runId, RunStatus status, RunEventType event, String detail) {
        // Audit first, status last: once the status leaves RUNNING, approvals and replans may proceed.
        audit.runEvent(runId, event, detail);
        WorkflowRun run = getRun(runId);
        run.changeStatus(status, clock.instant());
        runs.save(run);
    }

    private static TaskGraph rebuildGraph(List<TaskExecution> rows) {
        List<TaskNode> nodes = new ArrayList<>();
        for (TaskExecution row : rows) {
            nodes.add(new TaskNode(row.getTaskId(), row.getAgentType(), row.getDependsOn()));
        }
        TaskGraph graph = new TaskGraph(nodes);
        for (TaskExecution row : rows) {
            graph.restoreState(row.getTaskId(), row.getStatus(), row.getAttempts());
        }
        return graph;
    }

    private static PriorState priorState(List<TaskExecution> rows) {
        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        Map<String, List<String>> artifacts = new LinkedHashMap<>();
        for (TaskExecution row : rows) {
            if (row.getStatus() == TaskStatus.SUCCEEDED) {
                outputs.put(row.getTaskId(), JsonCodec.decode(row.getOutputs()));
                artifacts.put(row.getTaskId(), row.getArtifacts());
            }
        }
        return new PriorState(outputs, artifacts);
    }

    private Object lockFor(UUID runId) {
        return locks.computeIfAbsent(runId, id -> new Object());
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
            task.recordResult(JsonCodec.encode(result.outputs()), result.artifacts(),
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
