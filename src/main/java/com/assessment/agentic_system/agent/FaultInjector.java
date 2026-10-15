package com.assessment.agentic_system.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Deterministic failure switch for demos and tests: make the next N executions of an agent type
 * fail. Used to demonstrate failure handling now, and retries and rollback in later steps.
 */
@Component
public class FaultInjector {

    private final Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();

    public void failNext(String agentType, int times) {
        remaining.put(agentType, new AtomicInteger(times));
    }

    /** Consumes one pending failure for this type, if any. */
    public boolean shouldFail(String agentType) {
        AtomicInteger counter = remaining.get(agentType);
        return counter != null && counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    public void reset() {
        remaining.clear();
    }
}
