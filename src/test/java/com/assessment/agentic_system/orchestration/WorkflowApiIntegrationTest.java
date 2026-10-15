package com.assessment.agentic_system.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.agentic_system.agent.AgentTypes;
import com.assessment.agentic_system.agent.FaultInjector;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end through the real application context: HTTP API, async parallel engine, agents,
 * governance, H2 + Flyway, audit and metrics.
 */
@SpringBootTest(properties = "orchestration.retry.initial-backoff-ms=5")
class WorkflowApiIntegrationTest {

    private static final String REQUIREMENT = "Build a URL shortener with expiration and click analytics.";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FaultInjector faults;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    // ------------------------------------------------------------------ execution

    @Test
    void autonomousRunCompletesWithAllTasksSucceededAndFullAuditTrail() throws Exception {
        String runId = startRun("AUTONOMOUS", List.of());

        String finalState = awaitStatus(runId, "SUCCEEDED");

        assertThat(JsonPath.<Integer>read(finalState, "$.tasksSucceeded")).isEqualTo(6);
        String tasks = getJson("/api/v1/workflows/{id}/tasks", runId);
        assertThat(JsonPath.<List<String>>read(tasks, "$[*].status")).containsOnly("SUCCEEDED");
        assertThat(JsonPath.<List<String>>read(tasks, "$[?(@.taskId=='test')].outputs.testsPassed"))
                .containsExactly("20"); // 5 acceptance criteria x 4 tests each

        String events = getJson("/api/v1/workflows/{id}/events", runId);
        List<String> types = JsonPath.read(events, "$[*].eventType");
        assertThat(types.getFirst()).isEqualTo("RUN_CREATED");
        assertThat(types.getLast()).isEqualTo("RUN_SUCCEEDED");
        // 6 tasks x 3 transitions (READY, RUNNING, SUCCEEDED) + 6 agent results
        assertThat(types).filteredOn("TASK_TRANSITION"::equals).hasSize(18);
        assertThat(types).filteredOn("AGENT_RESULT"::equals).hasSize(6);

        List<String> succeededOrder = JsonPath.read(events,
                "$[?(@.eventType=='TASK_TRANSITION' && @.toStatus=='SUCCEEDED')].taskId");
        assertThat(succeededOrder.indexOf("requirements")).isLessThan(succeededOrder.indexOf("architecture"));
        assertThat(succeededOrder.getLast()).isEqualTo("documentation");
    }

    @Test
    void transientFailureIsRetriedAndTheRunRecovers() throws Exception {
        faults.failNext(AgentTypes.SECURITY, 2);
        String runId = startRun("AUTONOMOUS", List.of());

        awaitStatus(runId, "SUCCEEDED");

        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='security')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.taskId=='security')].attempts").value(3));
        String events = getJson("/api/v1/workflows/{id}/events", runId);
        assertThat(JsonPath.<List<String>>read(events,
                "$[?(@.eventType=='TASK_TRANSITION' && @.toStatus=='RETRYING')].taskId"))
                .containsExactly("security", "security");
    }

    @Test
    void exhaustedRetriesFailTheRunRollBackStagedWorkAndBlockDownstream() throws Exception {
        faults.failNext(AgentTypes.SECURITY, 10);
        String runId = startRun("AUTONOMOUS", List.of());

        awaitStatus(runId, "FAILED");

        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='security')].status").value("FAILED"))
                .andExpect(jsonPath("$[?(@.taskId=='security')].attempts").value(4))
                .andExpect(jsonPath("$[?(@.taskId=='security')].error")
                        .value("INJECTED_FAILURE: SECURITY agent failed (injected)"))
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$[?(@.taskId=='architecture')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.taskId=='test')].status").value("BLOCKED"))
                .andExpect(jsonPath("$[?(@.taskId=='documentation')].status").value("BLOCKED"));
    }

    // ------------------------------------------------------------------ governance

    @Test
    void approvalGatePausesTheRunUntilAnAuthorizedApproverApproves() throws Exception {
        String runId = startRun("CONTROLLED", List.of("CODE_MERGE"));

        awaitStatus(runId, "AWAITING_APPROVAL");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[?(@.taskId=='security')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.taskId=='test')].status").value("PENDING"));

        decide(runId, "implementation", "APPROVE", "tech-lead").andExpect(status().isAccepted());

        awaitStatus(runId, "SUCCEEDED");
        List<String> types = JsonPath.read(getJson("/api/v1/workflows/{id}/events", runId), "$[*].eventType");
        assertThat(types).containsSubsequence("RUN_PAUSED", "APPROVAL_GRANTED", "RUN_RESUMED", "RUN_SUCCEEDED");
    }

    @Test
    void rejectedApprovalCancelsRunAndReplanWithClarifiedRequirementCompletesIt() throws Exception {
        String runId = startRun("AMBIGUOUS", "CONTROLLED", List.of("CODE_MERGE"));
        awaitStatus(runId, "AWAITING_APPROVAL");

        decide(runId, "implementation", "REJECT", "tech-lead").andExpect(status().isAccepted());

        awaitStatus(runId, "CANCELLED");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("CANCELLED"))
                .andExpect(jsonPath("$[?(@.taskId=='test')].status").value("BLOCKED"));

        mvc.perform(post("/api/v1/workflows/{id}/replan", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirement":"Build a URL shortener; links expire after 30 days; analytics count unique visitors.",
                                 "reason":"Clarified expiry and analytics definitions"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requirementVersion").value(2));

        // The clarified plan still needs CODE_MERGE approval before implementation.
        awaitStatus(runId, "AWAITING_APPROVAL");
        decide(runId, "implementation", "APPROVE", "release-manager").andExpect(status().isAccepted());

        awaitStatus(runId, "SUCCEEDED");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='requirements')].outputs.requirementVersion").value("2"));
        List<String> types = JsonPath.read(getJson("/api/v1/workflows/{id}/events", runId), "$[*].eventType");
        assertThat(types).containsSubsequence("APPROVAL_REJECTED", "RUN_CANCELLED", "RUN_REPLANNED",
                "APPROVAL_GRANTED", "RUN_SUCCEEDED");
    }

    @Test
    void unauthorizedApproverIsRejected() throws Exception {
        String runId = startRun("CONTROLLED", List.of("CODE_MERGE"));
        awaitStatus(runId, "AWAITING_APPROVAL");

        decide(runId, "implementation", "APPROVE", "intern").andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/workflows/{id}", runId)).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
    }

    @Test
    void approvalOutsideAGateIsAConflict() throws Exception {
        String runId = startRun("AUTONOMOUS", List.of());
        awaitStatus(runId, "SUCCEEDED");

        decide(runId, "implementation", "APPROVE", "tech-lead").andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void ambiguousRequirementPausesAtDecisionGateAndClarificationReplansIt() throws Exception {
        String runId = startRun("AMBIGUOUS", "AUTONOMOUS", List.of(),
                "Build a fast, scalable URL shortener with analytics etc.");

        // Even in AUTONOMOUS mode, the AMBIGUOUS scenario stops for a human decision.
        awaitStatus(runId, "AWAITING_APPROVAL");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='requirements')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.taskId=='requirements')].outputs.ambiguityCount").value("3"))
                .andExpect(jsonPath("$[?(@.taskId=='architecture')].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("PENDING"));
        String events = getJson("/api/v1/workflows/{id}/events", runId);
        List<String> gateReasons = JsonPath.read(events,
                "$[?(@.taskId=='architecture' && @.toStatus=='AWAITING_APPROVAL')].detail");
        assertThat(gateReasons).singleElement().asString().startsWith("Decision gate").contains("fast");

        mvc.perform(post("/api/v1/workflows/{id}/replan", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requirement":"Build a URL shortener; links expire after 30 days; redirects respond within 50 ms; analytics count unique visitors per day.",
                                 "reason":"Product owner clarified performance and analytics"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requirementVersion").value(2));

        awaitStatus(runId, "SUCCEEDED");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='requirements')].outputs.ambiguityCount").value("0"))
                .andExpect(jsonPath("$[?(@.taskId=='requirements')].outputs.requirementVersion").value("2"));
    }

    @Test
    void ambiguousRequirementCanProceedOnRecordedAssumptions() throws Exception {
        String runId = startRun("AMBIGUOUS", "AUTONOMOUS", List.of(),
                "Build a fast, scalable URL shortener with analytics etc.");
        awaitStatus(runId, "AWAITING_APPROVAL");

        decide(runId, "architecture", "APPROVE", "tech-lead").andExpect(status().isAccepted());

        awaitStatus(runId, "SUCCEEDED");
        List<String> summary = JsonPath.read(getJson("/api/v1/workflows/{id}/tasks", runId),
                "$[?(@.taskId=='documentation')].outputs.engineeringSummary");
        assertThat(summary).singleElement().asString().contains("proceeded on assumptions").contains("100 ms");
    }

    @Test
    void brownfieldDefectIsReproducedFixedWithinBoundsAndRegressionTested() throws Exception {
        String runId = startRun("BROWNFIELD", "AUTONOMOUS", List.of(),
                "Fix defect: expired short links still redirect at the exact expiry time");

        String finalState = awaitStatus(runId, "SUCCEEDED");

        assertThat(JsonPath.<Integer>read(finalState, "$.tasksTotal")).isEqualTo(7);
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='impact')].outputs.impactedFiles")
                        .value("ShortUrl | UrlService | UrlController"))
                .andExpect(jsonPath("$[?(@.taskId=='reproduce')].outputs.resultBeforeFix").value("FAILED"))
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].outputs.scope").value("bounded"))
                .andExpect(jsonPath("$[?(@.taskId=='test')].outputs.reproductionTestPassing").value("true"));

        List<String> started = JsonPath.read(getJson("/api/v1/workflows/{id}/events", runId),
                "$[?(@.eventType=='TASK_TRANSITION' && @.toStatus=='SUCCEEDED')].taskId");
        assertThat(started.indexOf("reproduce")).isLessThan(started.indexOf("implementation"));
    }

    @Test
    void brownfieldDefectThatMatchesNoKnownCodeFailsWithoutRetrying() throws Exception {
        String runId = startRun("BROWNFIELD", "AUTONOMOUS", List.of(), "Fix defect: the logo is the wrong shade of blue");

        awaitStatus(runId, "FAILED");

        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='impact')].status").value("FAILED"))
                .andExpect(jsonPath("$[?(@.taskId=='impact')].attempts").value(1))
                .andExpect(jsonPath("$[?(@.taskId=='reproduce')].status").value("BLOCKED"));
    }

    @Test
    void demoFaultEndpointDrivesRetryExhaustionThroughTheApi() throws Exception {
        mvc.perform(post("/api/v1/demo/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentType\":\"SECURITY\",\"times\":10}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/demo/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentType\":\"DEPLOY\",\"times\":1}"))
                .andExpect(status().isBadRequest());

        String runId = startRun("AUTONOMOUS", List.of());

        awaitStatus(runId, "FAILED");
        getTasks(runId)
                .andExpect(jsonPath("$[?(@.taskId=='security')].attempts").value(4))
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("ROLLED_BACK"));
    }

    // ------------------------------------------------------------------ API edges and metrics

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"GREENFIELD\",\"requirement\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRunReturns404() throws Exception {
        mvc.perform(get("/api/v1/workflows/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void metricsReflectCompletedRuns() throws Exception {
        String runId = startRun("AUTONOMOUS", List.of());
        awaitStatus(runId, "SUCCEEDED");

        String metrics = getJson("/api/v1/metrics");
        assertThat(JsonPath.<Integer>read(metrics, "$.runsTotal")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Integer>read(metrics, "$.runsByStatus.SUCCEEDED")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Integer>read(metrics, "$.agentExecutions")).isGreaterThanOrEqualTo(6);
        assertThat(JsonPath.<Object>read(metrics, "$.runSuccessRate")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private String startRun(String autonomy, List<String> approvalRequiredFor) throws Exception {
        return startRun("GREENFIELD", autonomy, approvalRequiredFor);
    }

    private String startRun(String scenario, String autonomy, List<String> approvalRequiredFor) throws Exception {
        return startRun(scenario, autonomy, approvalRequiredFor, REQUIREMENT);
    }

    private String startRun(String scenario, String autonomy, List<String> approvalRequiredFor, String requirement)
            throws Exception {
        String approvals = approvalRequiredFor.stream().map(a -> "\"" + a + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        String body = mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"%s","requirement":"%s",
                                 "autonomyLevel":"%s","approvalRequiredFor":[%s]}
                                """.formatted(scenario, requirement, autonomy, approvals)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tasksTotal").value(scenario.equals("BROWNFIELD") ? 7 : 6))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.runId");
    }

    private ResultActions decide(String runId, String taskId, String decision, String approver) throws Exception {
        return mvc.perform(post("/api/v1/workflows/{id}/approvals", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"taskId":"%s","decision":"%s","approver":"%s","reason":"reviewed"}
                        """.formatted(taskId, decision, approver)));
    }

    private ResultActions getTasks(String runId) throws Exception {
        return mvc.perform(get("/api/v1/workflows/{id}/tasks", runId)).andExpect(status().isOk());
    }

    private String getJson(String path, Object... vars) throws Exception {
        return mvc.perform(get(path, vars)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Polls until the run reaches {@code expected}. Fails fast if it settles in a different stable
     * state (paused or finished), since those only change on an explicit action.
     */
    private String awaitStatus(String runId, String expected) throws Exception {
        List<String> stable = List.of("SUCCEEDED", "FAILED", "CANCELLED", "AWAITING_APPROVAL");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = getJson("/api/v1/workflows/{id}", runId);
            String status = JsonPath.read(body, "$.status");
            if (status.equals(expected)) {
                return body;
            }
            if (stable.contains(status)) {
                throw new AssertionError("Run " + runId + " settled in " + status + ", expected " + expected
                        + "\n" + getJson("/api/v1/workflows/{id}/events", runId));
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Run " + runId + " did not reach " + expected + " within 10s");
    }
}
