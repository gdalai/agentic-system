package com.assessment.agentic_system.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot handed to an agent. Agents read upstream outputs from here; they cannot
 * mutate shared workflow state.
 *
 * @param upstreamOutputs outputs of tasks completed so far, keyed by task id
 */
public record AgentContext(
        UUID runId,
        String taskId,
        String requirement,
        int requirementVersion,
        Map<String, Map<String, String>> upstreamOutputs) {

    public AgentContext {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        if (upstreamOutputs != null) {
            upstreamOutputs.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        }
        upstreamOutputs = java.util.Collections.unmodifiableMap(copy);
    }

    /** Convenience lookup of one upstream output value, or null if absent. */
    public String upstream(String taskId, String key) {
        Map<String, String> outputs = upstreamOutputs.get(taskId);
        return outputs == null ? null : outputs.get(key);
    }
}
