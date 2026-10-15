package com.assessment.agentic_system.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves agents by type. Fails fast at startup on duplicates and at planning time on unknown types. */
@Component
public class AgentRegistry {

    private final Map<String, EngineeringAgent> byType;

    public AgentRegistry(List<EngineeringAgent> agents) {
        Map<String, EngineeringAgent> map = new LinkedHashMap<>();
        for (EngineeringAgent agent : agents) {
            EngineeringAgent previous = map.putIfAbsent(agent.type(), agent);
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent type '" + agent.type() + "': "
                        + previous.getClass().getSimpleName() + " and " + agent.getClass().getSimpleName());
            }
        }
        this.byType = Map.copyOf(map);
    }

    public EngineeringAgent get(String type) {
        EngineeringAgent agent = byType.get(type);
        if (agent == null) {
            throw new IllegalArgumentException("No agent registered for type '" + type + "'");
        }
        return agent;
    }

    public boolean supports(String type) {
        return byType.containsKey(type);
    }

    /** Throws if any of the given types has no registered agent. */
    public void requireAll(Collection<String> types) {
        List<String> missing = types.stream().filter(t -> !byType.containsKey(t)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No agents registered for types " + missing);
        }
    }

    public Set<String> types() {
        return byType.keySet();
    }
}
