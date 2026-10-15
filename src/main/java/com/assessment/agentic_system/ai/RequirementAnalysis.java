package com.assessment.agentic_system.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structured result of requirement analysis, parsed from a line-oriented model reply:
 *
 * <pre>
 * CRITERION: ...
 * AMBIGUITY: ...
 * ASSUMPTION: ...
 * </pre>
 *
 * Unrecognised lines are ignored, so a chatty model reply still parses; a reply with no
 * acceptance criteria is rejected.
 */
public record RequirementAnalysis(List<String> criteria, List<String> ambiguities, List<String> assumptions) {

    public RequirementAnalysis {
        criteria = List.copyOf(criteria);
        ambiguities = List.copyOf(ambiguities);
        assumptions = List.copyOf(assumptions);
    }

    public boolean isAmbiguous() {
        return !ambiguities.isEmpty();
    }

    public static RequirementAnalysis parse(String reply) {
        List<String> criteria = new ArrayList<>();
        List<String> ambiguities = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        if (reply != null) {
            for (String raw : reply.split("\\R")) {
                String line = raw.strip().replaceFirst("^[-*•]\\s*", "");
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String tag = line.substring(0, colon).strip().toUpperCase(Locale.ROOT);
                String value = line.substring(colon + 1).strip();
                if (value.isEmpty()) {
                    continue;
                }
                switch (tag) {
                    case "CRITERION" -> criteria.add(value);
                    case "AMBIGUITY" -> ambiguities.add(value);
                    case "ASSUMPTION" -> assumptions.add(value);
                    default -> {
                        // ignore commentary
                    }
                }
            }
        }
        if (criteria.isEmpty()) {
            throw new InvalidAnalysisException("Model reply contained no CRITERION lines");
        }
        return new RequirementAnalysis(criteria, ambiguities, assumptions);
    }

    /** The model reply could not be turned into a usable analysis. Not worth retrying blindly. */
    public static class InvalidAnalysisException extends RuntimeException {
        public InvalidAnalysisException(String message) {
            super(message);
        }
    }
}
