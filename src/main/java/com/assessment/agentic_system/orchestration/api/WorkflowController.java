package com.assessment.agentic_system.orchestration.api;

import com.assessment.agentic_system.orchestration.WorkflowService;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.ApprovalRequest;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.EventView;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.ReplanRequest;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.TaskView;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.WorkflowRequest;
import com.assessment.agentic_system.orchestration.api.WorkflowDtos.WorkflowRunView;
import com.assessment.agentic_system.orchestration.persistence.WorkflowRun;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    /** Accepts a requirement and starts a run asynchronously. Poll the Location to follow progress. */
    @PostMapping
    public ResponseEntity<WorkflowRunView> start(@Valid @RequestBody WorkflowRequest request) {
        WorkflowRun run = service.start(request.scenario(), request.requirement(), request.autonomyOrDefault(),
                request.approvalRequiredFor());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(run.getId()).toUri();
        return ResponseEntity.accepted().location(location)
                .body(WorkflowRunView.from(run, service.getTasks(run.getId())));
    }

    @GetMapping("/{runId}")
    public WorkflowRunView get(@PathVariable UUID runId) {
        return WorkflowRunView.from(service.getRun(runId), service.getTasks(runId));
    }

    @GetMapping("/{runId}/tasks")
    public List<TaskView> tasks(@PathVariable UUID runId) {
        return service.getTasks(runId).stream().map(TaskView::from).toList();
    }

    @GetMapping("/{runId}/events")
    public List<EventView> events(@PathVariable UUID runId) {
        return service.getEvents(runId).stream().map(EventView::from).toList();
    }

    /** Approve or reject a task parked at an approval gate. The run resumes in the background. */
    @PostMapping("/{runId}/approvals")
    public ResponseEntity<WorkflowRunView> decide(@PathVariable UUID runId, @Valid @RequestBody ApprovalRequest request) {
        WorkflowRun run = service.decide(runId, request.taskId(), request.decision(), request.approver(),
                request.reason());
        return ResponseEntity.accepted().body(WorkflowRunView.from(run, service.getTasks(runId)));
    }

    /** Record a changed requirement and re-run only the tasks it invalidates. */
    @PostMapping("/{runId}/replan")
    public ResponseEntity<WorkflowRunView> replan(@PathVariable UUID runId, @Valid @RequestBody ReplanRequest request) {
        WorkflowRun run = service.replan(runId, request.requirement(), request.fromTaskId(), request.reason());
        return ResponseEntity.accepted().body(WorkflowRunView.from(run, service.getTasks(runId)));
    }
}
