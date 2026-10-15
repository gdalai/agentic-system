package com.assessment.agentic_system.agent;

import java.util.List;
import java.util.Map;

/**
 * What an agent produced. A result is evidence for the engine to evaluate, not a decision.
 *
 * @param errorCode machine-readable failure reason; null on success
 */
public record AgentResult(
        boolean succeeded,
        Map<String, String> outputs,
        List<String> artifacts,
        String summary,
        String errorCode) {

    public AgentResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static AgentResult success(String summary, Map<String, String> outputs, List<String> artifacts) {
        return new AgentResult(true, outputs, artifacts, summary, null);
    }

    public static AgentResult failure(String errorCode, String summary) {
        return new AgentResult(false, Map.of(), List.of(), summary, errorCode);
    }
}
