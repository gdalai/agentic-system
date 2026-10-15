package com.assessment.agentic_system.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.agent.Agents.ArchitectureAgent;
import com.assessment.agentic_system.agent.Agents.DefectReproductionAgent;
import com.assessment.agentic_system.agent.Agents.DocumentationAgent;
import com.assessment.agentic_system.agent.Agents.ImpactAnalysisAgent;
import com.assessment.agentic_system.agent.Agents.ImplementationAgent;
import com.assessment.agentic_system.agent.Agents.RequirementAgent;
import com.assessment.agentic_system.agent.Agents.SecurityAgent;
import com.assessment.agentic_system.agent.Agents.TestAgent;
import com.assessment.agentic_system.ai.LlmClient;
import com.assessment.agentic_system.ai.MockLlmClient;
import com.assessment.agentic_system.ai.RequirementAnalyzer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentsTest {

    private final FaultInjector faults = new FaultInjector();

    private final RequirementAnalyzer analyzer = new RequirementAnalyzer(new MockLlmClient());

    private List<EngineeringAgent> all() {
        return List.of(new RequirementAgent(faults, analyzer), new ArchitectureAgent(faults),
                new ImplementationAgent(faults), new SecurityAgent(faults), new TestAgent(faults),
                new DocumentationAgent(faults), new ImpactAnalysisAgent(faults), new DefectReproductionAgent(faults));
    }

    private static AgentContext context(Map<String, Map<String, String>> upstream) {
        return context("Build a URL shortener", upstream);
    }

    private static AgentContext context(String requirement, Map<String, Map<String, String>> upstream) {
        return new AgentContext(UUID.randomUUID(), "t", requirement, 1, upstream);
    }

    @Test
    void registryResolvesAllAgentTypes() {
        AgentRegistry registry = new AgentRegistry(all());

        assertThat(registry.types()).containsExactlyInAnyOrder(AgentTypes.REQUIREMENT, AgentTypes.ARCHITECTURE,
                AgentTypes.IMPLEMENTATION, AgentTypes.SECURITY, AgentTypes.TEST, AgentTypes.DOCUMENTATION,
                AgentTypes.IMPACT_ANALYSIS, AgentTypes.DEFECT_REPRODUCTION);
        assertThat(registry.get(AgentTypes.TEST)).isInstanceOf(TestAgent.class);
    }

    @Test
    void requirementAgentReportsCriteriaAndAmbiguitiesFromTheModel() {
        RequirementAgent agent = new RequirementAgent(faults, analyzer);

        AgentResult clear = agent.execute(context("Build a URL shortener with expiration and click analytics.", Map.of()));
        assertThat(clear.outputs()).containsEntry("criteriaCount", "5").containsEntry("ambiguityCount", "0")
                .containsEntry("analyzer", "mock-deterministic");

        AgentResult vague = agent.execute(context("Build a fast, scalable URL shortener with analytics etc.", Map.of()));
        assertThat(vague.succeeded()).isTrue();
        assertThat(vague.outputs()).containsEntry("ambiguityCount", "3");
        assertThat(vague.outputs().get("assumptions")).contains("100 ms");
    }

    @Test
    void requirementAgentMapsProviderErrorsToRetryableAndBadOutputToNonRetryable() {
        RequirementAnalyzer down = new RequirementAnalyzer(new LlmClient() {
            @Override public String name() { return "down"; }
            @Override public String complete(String s, String u) {
                throw new LlmClient.LlmUnavailableException("503", null);
            }
        });
        RequirementAnalyzer garbage = new RequirementAnalyzer(new LlmClient() {
            @Override public String name() { return "garbage"; }
            @Override public String complete(String s, String u) { return "Sure! Here are some thoughts."; }
        });

        assertThat(new RequirementAgent(faults, down).execute(context(Map.of())).errorCode())
                .isEqualTo("LLM_UNAVAILABLE");
        assertThat(new RequirementAgent(faults, garbage).execute(context(Map.of())).errorCode())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void brownfieldAgentsTraceTheDefectToCodeAndATest() {
        String defect = "Fix defect: expired short links still redirect at the exact expiry time";
        AgentResult impact = new ImpactAnalysisAgent(faults).execute(context(defect, Map.of()));

        assertThat(impact.outputs().get("impactedClasses"))
                .contains("ShortUrl.isExpiredAt", "UrlService.resolve", "UrlController.redirect");
        assertThat(impact.outputs().get("impactedFiles")).isEqualTo("ShortUrl | UrlService | UrlController");

        AgentResult reproduce = new DefectReproductionAgent(faults)
                .execute(context(defect, Map.of("impact", impact.outputs())));
        assertThat(reproduce.outputs().get("failingTest")).startsWith("UrlServiceTest#reproduces_");

        AgentResult fix = new ImplementationAgent(faults).execute(context(defect, Map.of("impact", impact.outputs())));
        assertThat(fix.outputs()).containsEntry("scope", "bounded").containsEntry("filesChanged", "3");
    }

    @Test
    void impactAnalysisFailsWhenNothingMatches() {
        AgentResult result = new ImpactAnalysisAgent(faults).execute(context("Make the logo blue", Map.of()));

        assertThat(result.errorCode()).isEqualTo("IMPACT_UNKNOWN");
    }

    @Test
    void registryRejectsDuplicatesAndUnknownTypes() {
        assertThatThrownBy(() -> new AgentRegistry(List.of(new TestAgent(faults), new TestAgent(faults))))
                .isInstanceOf(IllegalStateException.class);

        AgentRegistry registry = new AgentRegistry(all());
        assertThatThrownBy(() -> registry.get("DEPLOY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.requireAll(List.of(AgentTypes.TEST, "DEPLOY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEPLOY");
    }

    @Test
    void agentsAreDeterministic() {
        RequirementAgent agent = new RequirementAgent(faults, analyzer);

        assertThat(agent.execute(context(Map.of()))).isEqualTo(agent.execute(context(Map.of())));
    }

    @Test
    void downstreamAgentsUseUpstreamOutputs() {
        AgentResult result = new TestAgent(faults).execute(context(Map.of("requirements", Map.of("criteriaCount", "3"))));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.outputs()).containsEntry("testsRun", "12").containsEntry("testsFailed", "0");
    }

    @Test
    void faultInjectorFailsExactlyNTimes() {
        faults.failNext(AgentTypes.SECURITY, 2);
        SecurityAgent agent = new SecurityAgent(faults);

        assertThat(agent.execute(context(Map.of())).errorCode()).isEqualTo("INJECTED_FAILURE");
        assertThat(agent.execute(context(Map.of())).errorCode()).isEqualTo("INJECTED_FAILURE");
        assertThat(agent.execute(context(Map.of())).succeeded()).isTrue();
    }

    @Test
    void contextIsImmutable() {
        AgentContext ctx = context(Map.of("a", Map.of("k", "v")));

        assertThatThrownBy(() -> ctx.upstreamOutputs().put("b", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(ctx.upstream("a", "k")).isEqualTo("v");
        assertThat(ctx.upstream("missing", "k")).isNull();
    }
}
