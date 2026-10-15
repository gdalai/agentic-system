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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end through the real application context: HTTP API, async engine, agents, H2 + Flyway, audit.
 */
@SpringBootTest
class WorkflowApiIntegrationTest {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

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

    @Test
    void greenfieldRunCompletesWithAllTasksSucceededAndFullAuditTrail() throws Exception {
        String runId = startRun("GREENFIELD");

        String finalState = awaitTerminal(runId);

        assertThat(JsonPath.<String>read(finalState, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(finalState, "$.tasksSucceeded")).isEqualTo(6);

        String tasks = mvc.perform(get("/api/v1/workflows/{id}/tasks", runId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(tasks, "$[*].status")).containsOnly("SUCCEEDED");
        List<String> testsPassed = JsonPath.read(tasks, "$[?(@.taskId=='test')].outputs.testsPassed");
        assertThat(testsPassed).containsExactly("12");

        String events = mvc.perform(get("/api/v1/workflows/{id}/events", runId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(events, "$[*].eventType");
        assertThat(types.getFirst()).isEqualTo("RUN_CREATED");
        assertThat(types.getLast()).isEqualTo("RUN_SUCCEEDED");
        // 6 tasks x 3 transitions (READY, RUNNING, SUCCEEDED) + 6 agent results
        assertThat(types).filteredOn("TASK_TRANSITION"::equals).hasSize(18);
        assertThat(types).filteredOn("AGENT_RESULT"::equals).hasSize(6);

        // Audit order respects dependencies: requirements finishes before architecture starts.
        List<String> succeededOrder = JsonPath.read(events,
                "$[?(@.eventType=='TASK_TRANSITION' && @.toStatus=='SUCCEEDED')].taskId");
        assertThat(succeededOrder.indexOf("requirements")).isLessThan(succeededOrder.indexOf("architecture"));
        assertThat(succeededOrder.getLast()).isEqualTo("documentation");
    }

    @Test
    void failingAgentFailsRunAndBlocksDownstreamTasks() throws Exception {
        faults.failNext(AgentTypes.SECURITY, 1);
        String runId = startRun("GREENFIELD");

        String finalState = awaitTerminal(runId);

        assertThat(JsonPath.<String>read(finalState, "$.status")).isEqualTo("FAILED");
        mvc.perform(get("/api/v1/workflows/{id}/tasks", runId))
                .andExpect(jsonPath("$[?(@.taskId=='security')].status").value("FAILED"))
                .andExpect(jsonPath("$[?(@.taskId=='security')].error").value("INJECTED_FAILURE: SECURITY agent failed (injected)"))
                .andExpect(jsonPath("$[?(@.taskId=='implementation')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.taskId=='test')].status").value("BLOCKED"))
                .andExpect(jsonPath("$[?(@.taskId=='documentation')].status").value("BLOCKED"));
    }

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

    private String startRun(String scenario) throws Exception {
        String body = mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"%s",
                                 "requirement":"Build a URL shortener with expiration and click analytics.",
                                 "autonomyLevel":"CONTROLLED",
                                 "approvalRequiredFor":["CODE_MERGE","RELEASE"]}
                                """.formatted(scenario)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tasksTotal").value(6))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.runId");
    }

    private String awaitTerminal(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/api/v1/workflows/{id}", runId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (TERMINAL.contains(JsonPath.<String>read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + runId + " did not finish within 10s");
    }
}
