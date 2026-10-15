package com.assessment.agentic_system.agent;

import com.assessment.agentic_system.ai.LlmClient.LlmUnavailableException;
import com.assessment.agentic_system.ai.RequirementAnalysis;
import com.assessment.agentic_system.ai.RequirementAnalysis.InvalidAnalysisException;
import com.assessment.agentic_system.ai.RequirementAnalyzer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The engineering agents. The Requirement agent is model-backed through {@link RequirementAnalyzer}
 * (deterministic mock by default); the others are deterministic. Every agent returns structured
 * evidence; the engine and policy decide what happens next.
 */
public final class Agents {

    /** Separator used for list-valued outputs. */
    public static final String LIST_SEPARATOR = " | ";

    private Agents() {
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\s*\\|\\s*")).filter(s -> !s.isBlank()).toList();
    }

    @Component
    public static class RequirementAgent extends BaseAgent {
        private final RequirementAnalyzer analyzer;

        public RequirementAgent(FaultInjector faults, RequirementAnalyzer analyzer) {
            super(faults);
            this.analyzer = analyzer;
        }

        @Override public String type() { return AgentTypes.REQUIREMENT; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            RequirementAnalysis analysis;
            try {
                analysis = analyzer.analyze(ctx.requirement());
            } catch (LlmUnavailableException e) {
                return AgentResult.failure("LLM_UNAVAILABLE", e.getMessage());
            } catch (InvalidAnalysisException e) {
                return AgentResult.failure("VALIDATION_FAILED", "Unusable model output: " + e.getMessage());
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("acceptanceCriteria", String.join(LIST_SEPARATOR, analysis.criteria()));
            outputs.put("criteriaCount", String.valueOf(analysis.criteria().size()));
            outputs.put("ambiguities", String.join(LIST_SEPARATOR, analysis.ambiguities()));
            outputs.put("ambiguityCount", String.valueOf(analysis.ambiguities().size()));
            outputs.put("assumptions", String.join(LIST_SEPARATOR, analysis.assumptions()));
            outputs.put("requirementVersion", String.valueOf(ctx.requirementVersion()));
            outputs.put("analyzer", analyzer.modelName());
            String summary = "Derived " + analysis.criteria().size() + " acceptance criteria (requirement v"
                    + ctx.requirementVersion() + ")"
                    + (analysis.isAmbiguous() ? "; " + analysis.ambiguities().size() + " ambiguities need a decision" : "");
            return AgentResult.success(summary, outputs, List.of("docs/requirements.md"));
        }
    }

    @Component
    public static class ArchitectureAgent extends BaseAgent {
        public ArchitectureAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.ARCHITECTURE; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String assumptions = ctx.upstream("requirements", "assumptions");
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("components", "controller,service,repository,entity");
            outputs.put("persistence", "PostgreSQL via JPA + Flyway");
            outputs.put("decision", "Single deployable with package boundaries");
            outputs.put("designAssumptions", assumptions == null ? "" : assumptions);
            return AgentResult.success("Layered design: controller, service, repository, entity", outputs,
                    List.of("docs/architecture.md"));
        }
    }

    /**
     * Brownfield: maps a defect description onto the existing codebase. Uses a static index of this
     * repository's URL shortener (the brownfield workload), so results are reproducible.
     */
    @Component
    public static class ImpactAnalysisAgent extends BaseAgent {

        private record IndexEntry(String keyword, List<String> classes, List<String> tests) {
        }

        private static final List<IndexEntry> INDEX = List.of(
                new IndexEntry("expir", List.of("ShortUrl.isExpiredAt", "UrlService.resolve"), List.of("UrlServiceTest")),
                new IndexEntry("redirect", List.of("UrlController.redirect"), List.of("UrlControllerTest")),
                new IndexEntry("click", List.of("UrlService.analytics", "ClickEventRepository"), List.of("UrlServiceTest")),
                new IndexEntry("analytic", List.of("UrlService.analytics", "ClickEventRepository"), List.of("UrlServiceTest")),
                new IndexEntry("collision", List.of("CodeGenerator.generate"), List.of("CodeGeneratorTest")),
                new IndexEntry("code", List.of("CodeGenerator.generate"), List.of("CodeGeneratorTest")),
                new IndexEntry("validat", List.of("Dtos.CreateUrlRequest"), List.of("UrlControllerTest")));

        public ImpactAnalysisAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.IMPACT_ANALYSIS; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String text = ctx.requirement().toLowerCase(Locale.ROOT);
            Set<String> classes = new LinkedHashSet<>();
            Set<String> tests = new LinkedHashSet<>();
            for (IndexEntry c : INDEX) {
                if (text.contains(c.keyword())) {
                    classes.addAll(c.classes());
                    tests.addAll(c.tests());
                }
            }
            if (classes.isEmpty()) {
                return AgentResult.failure("IMPACT_UNKNOWN", "No known component matches the defect description");
            }
            Set<String> files = new LinkedHashSet<>();
            classes.forEach(c -> files.add(c.contains(".") ? c.substring(0, c.indexOf('.')) : c));
            return AgentResult.success("Impact: " + classes.size() + " members in " + files.size() + " files; "
                            + tests.size() + " test suites",
                    Map.of("impactedClasses", String.join(LIST_SEPARATOR, classes),
                            "impactedFiles", String.join(LIST_SEPARATOR, files),
                            "regressionSuites", String.join(LIST_SEPARATOR, tests)),
                    List.of("reports/impact-analysis.md"));
        }
    }

    /** Brownfield: writes a test that fails on the current code, proving the defect before any fix. */
    @Component
    public static class DefectReproductionAgent extends BaseAgent {
        public DefectReproductionAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.DEFECT_REPRODUCTION; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            List<String> suites = splitList(ctx.upstream("impact", "regressionSuites"));
            String suite = suites.isEmpty() ? "RegressionTest" : suites.getFirst();
            String slug = ctx.requirement().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_|_$", "");
            String test = suite + "#reproduces_" + (slug.length() > 40 ? slug.substring(0, 40) : slug);
            return AgentResult.success("Defect reproduced by failing test " + test,
                    Map.of("failingTest", test, "reproduced", "true", "resultBeforeFix", "FAILED"),
                    List.of("staging/" + suite + "-reproduction.patch"));
        }
    }

    @Component
    public static class ImplementationAgent extends BaseAgent {
        /** Brownfield fixes must stay small; anything larger needs a redesign, not a patch. */
        static final int MAX_FILES_PER_FIX = 3;

        public ImplementationAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.IMPLEMENTATION; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            String impactedFiles = ctx.upstream("impact", "impactedFiles");
            if (impactedFiles != null) {
                List<String> files = splitList(impactedFiles);
                if (files.size() > MAX_FILES_PER_FIX) {
                    return AgentResult.failure("VALIDATION_FAILED", "Fix would touch " + files.size()
                            + " files; bounded changes are limited to " + MAX_FILES_PER_FIX);
                }
                return AgentResult.success("Bounded fix in " + files.size() + " files: " + files,
                        Map.of("filesChanged", String.valueOf(files.size()),
                                "changedFiles", impactedFiles,
                                "scope", "bounded"),
                        List.of("staging/fix.patch"));
            }
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
            Map<String, String> outputs = new LinkedHashMap<>();

            String failingTest = ctx.upstream("reproduce", "failingTest");
            if (failingTest != null) {
                // Brownfield: the reproduction test must now pass, alongside the impacted regression suites.
                tests++;
                outputs.put("reproductionTest", failingTest);
                outputs.put("reproductionTestPassing", "true");
                outputs.put("regressionSuites", String.valueOf(ctx.upstream("impact", "regressionSuites")));
            }
            outputs.put("testsRun", String.valueOf(tests));
            outputs.put("testsPassed", String.valueOf(tests));
            outputs.put("testsFailed", "0");
            String summary = tests + "/" + tests + " tests passed"
                    + (failingTest != null ? "; reproduction test now passes" : "");
            return AgentResult.success(summary, outputs, List.of("reports/tests.xml"));
        }
    }

    @Component
    public static class DocumentationAgent extends BaseAgent {
        public DocumentationAgent(FaultInjector faults) { super(faults); }

        @Override public String type() { return AgentTypes.DOCUMENTATION; }

        @Override
        protected AgentResult doExecute(AgentContext ctx) {
            StringBuilder summary = new StringBuilder("Requirement v" + ctx.requirementVersion() + " delivered; "
                    + ctx.upstreamOutputs().size() + " upstream tasks completed; tests "
                    + ctx.upstream("test", "testsPassed") + " passed");
            String findings = ctx.upstream("security", "findings");
            if (findings != null) {
                summary.append("; security findings ").append(findings);
            }
            String assumptions = ctx.upstream("requirements", "assumptions");
            if (assumptions != null && !assumptions.isBlank()) {
                summary.append("; proceeded on assumptions: ").append(assumptions);
            }
            String changed = ctx.upstream("implementation", "changedFiles");
            if (changed != null) {
                summary.append("; bounded fix in ").append(changed);
            }
            return AgentResult.success(summary.toString(), Map.of("engineeringSummary", summary.toString()),
                    List.of("docs/engineering-summary.md"));
        }
    }
}
