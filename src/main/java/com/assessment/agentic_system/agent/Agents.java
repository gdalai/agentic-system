package com.assessment.agentic_system.agent;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic agents. Each produces fixed, structured output derived from its inputs so runs are
 * reproducible in CI without any model provider. An LLM-backed implementation can replace any of
 * them later behind the same {@link EngineeringAgent} contract.
 */
public final class Agents {

    private Agents() {
    }

    @Component
    public static class RequirementAgent extends BaseAgent {
        public RequirementAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.REQUIREMENT; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String criteria = String.join(" | ",
                    "AC1: Implement: " + ctx.requirement(),
                    "AC2: Invalid input is rejected with a 4xx response",
                    "AC3: Behaviour is covered by automated tests");
            return AgentResult.success("Derived 3 acceptance criteria (requirement v" + ctx.requirementVersion() + ")",
                    Map.of("acceptanceCriteria", criteria,
                            "criteriaCount", "3",
                            "requirementVersion", String.valueOf(ctx.requirementVersion())),
                    List.of("docs/requirements.md"));
        }
    }

    @Component
    public static class ArchitectureAgent extends BaseAgent {
        public ArchitectureAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.ARCHITECTURE; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            return AgentResult.success("Layered design: controller, service, repository, entity",
                    Map.of("components", "controller,service,repository,entity",
                            "persistence", "PostgreSQL via JPA + Flyway",
                            "decision", "Single deployable with package boundaries"),
                    List.of("docs/architecture.md"));
        }
    }

    @Component
    public static class ImplementationAgent extends BaseAgent {
        public ImplementationAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.IMPLEMENTATION; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String components = ctx.upstream("architecture", "components");
            int files = components == null ? 1 : components.split(",").length;
            return AgentResult.success("Implemented " + files + " components",
                    Map.of("filesChanged", String.valueOf(files),
                            "basedOn", components == null ? "none" : components),
                    List.of("staging/implementation.patch"));
        }
    }

    @Component
    public static class SecurityAgent extends BaseAgent {
        public SecurityAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.SECURITY; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            return AgentResult.success("Security review: 0 findings",
                    Map.of("findings", "0",
                            "checks", "input-validation,open-redirect,injection,secrets"),
                    List.of("reports/security.md"));
        }
    }

    @Component
    public static class TestAgent extends BaseAgent {
        public TestAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.TEST; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String criteria = ctx.upstream("requirements", "criteriaCount");
            int tests = 4 * (criteria == null ? 1 : Integer.parseInt(criteria));
            return AgentResult.success(tests + "/" + tests + " tests passed",
                    Map.of("testsRun", String.valueOf(tests),
                            "testsPassed", String.valueOf(tests),
                            "testsFailed", "0"),
                    List.of("reports/tests.xml"));
        }
    }

    @Component
    public static class DocumentationAgent extends BaseAgent {
        public DocumentationAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.DOCUMENTATION; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String summary = "Requirement v" + ctx.requirementVersion() + " delivered; "
                    + ctx.upstreamOutputs().size() + " upstream tasks completed; tests "
                    + ctx.upstream("test", "testsPassed") + " passed; security findings "
                    + ctx.upstream("security", "findings");
            return AgentResult.success(summary, Map.of("engineeringSummary", summary),
                    List.of("docs/engineering-summary.md"));
        }
    }
}
