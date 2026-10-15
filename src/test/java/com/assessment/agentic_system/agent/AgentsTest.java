package com.assessment.agentic_system.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.agent.Agents.ArchitectureAgent;
import com.assessment.agentic_system.agent.Agents.DocumentationAgent;
import com.assessment.agentic_system.agent.Agents.ImplementationAgent;
import com.assessment.agentic_system.agent.Agents.RequirementAgent;
import com.assessment.agentic_system.agent.Agents.SecurityAgent;
import com.assessment.agentic_system.agent.Agents.TestAgent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentsTest {

    private final FaultInjector faults = new FaultInjector();

    private List<EngineeringAgent> all() {
        return List.of(new RequirementAgent(faults), new ArchitectureAgent(faults), new ImplementationAgent(faults),
                new SecurityAgent(faults), new TestAgent(faults), new DocumentationAgent(faults));
    }

    private static AgentContext context(Map<String, Map<String, String>> upstream) {
        return new AgentContext(UUID.randomUUID(), "t", "Build a URL shortener", 1, upstream);
    }

    @Test
    void registryResolvesAllSixTypes() {
        AgentRegistry registry = new AgentRegistry(all());

        assertThat(registry.types()).containsExactlyInAnyOrder(AgentTypes.REQUIREMENT, AgentTypes.ARCHITECTURE,
                AgentTypes.IMPLEMENTATION, AgentTypes.SECURITY, AgentTypes.TEST, AgentTypes.DOCUMENTATION);
        assertThat(registry.get(AgentTypes.TEST)).isInstanceOf(TestAgent.class);
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
        RequirementAgent agent = new RequirementAgent(faults);

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
