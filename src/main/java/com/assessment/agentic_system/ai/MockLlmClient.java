package com.assessment.agentic_system.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a model provider. It answers the requirement-analysis prompt with
 * rule-based output in the same line format a real model is asked for, so the parsing,
 * validation and decision gates are exercised identically in CI without credentials.
 */
public class MockLlmClient implements LlmClient {

    /** Vague words with no measurable target, and the assumption the mock proposes for each. */
    private static final Map<String, String> VAGUE = new LinkedHashMap<>();

    static {
        VAGUE.put("fast", "Redirect p95 latency under 100 ms on a single instance");
        VAGUE.put("quick", "Redirect p95 latency under 100 ms on a single instance");
        VAGUE.put("scalable", "Sustain 100 requests per second on a single instance");
        VAGUE.put("secure", "Validate all input and reject non-HTTP(S) target URLs");
        VAGUE.put("user-friendly", "Clear error messages using RFC 9457 problem details");
        VAGUE.put("robust", "All failures return a structured error and are covered by tests");
        VAGUE.put("efficient", "No endpoint performs more than two database queries");
        VAGUE.put("etc", "Scope is limited to the features explicitly named");
        VAGUE.put("various", "Scope is limited to the features explicitly named");
        VAGUE.put("some", "Scope is limited to the features explicitly named");
        VAGUE.put("tbd", "Undecided items are out of scope for this iteration");
    }

    private static final java.util.Set<String> OPEN_ENDED = java.util.Set.of("etc", "various", "some", "tbd");

    private static final Pattern WORD = Pattern.compile("[a-z][a-z-]*");

    @Override
    public String name() {
        return "mock-deterministic";
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String requirement = userPrompt.startsWith(RequirementAnalyzer.USER_PREFIX)
                ? userPrompt.substring(RequirementAnalyzer.USER_PREFIX.length())
                : userPrompt;
        String text = requirement.toLowerCase(Locale.ROOT);

        List<String> lines = new ArrayList<>();
        for (String criterion : criteria(text)) {
            lines.add("CRITERION: " + criterion);
        }

        List<String> words = WORD.matcher(text).results().map(m -> m.group()).toList();
        if (words.size() < 5) {
            lines.add("AMBIGUITY: Requirement is too brief to define scope");
            lines.add("ASSUMPTION: Deliver the smallest feature set implied by the wording");
        }
        VAGUE.forEach((word, assumption) -> {
            if (words.contains(word)) {
                lines.add("AMBIGUITY: '" + word + "' " + (OPEN_ENDED.contains(word)
                        ? "leaves the scope open-ended" : "has no measurable target"));
                lines.add("ASSUMPTION: " + assumption);
            }
        });
        return String.join("\n", lines);
    }

    private static List<String> criteria(String text) {
        List<String> criteria = new ArrayList<>();
        if (text.contains("url") || text.contains("link") || text.contains("shorten")) {
            criteria.add("A valid http(s) URL can be shortened and the short link redirects with HTTP 302");
        }
        if (text.contains("expir")) {
            criteria.add("A link at or past its expiry time returns HTTP 410 and is not redirected");
        }
        if (text.contains("analytic") || text.contains("click")) {
            criteria.add("Each redirect is counted and exposed through an analytics endpoint");
        }
        if (text.contains("fix") || text.contains("defect") || text.contains("bug")) {
            criteria.add("The reported defect is reproduced by a failing test before the fix");
            criteria.add("The full existing regression suite passes after the fix");
        }
        criteria.add("Invalid input is rejected with a 4xx problem-details response");
        criteria.add("Behaviour is covered by automated tests");
        return criteria;
    }
}
