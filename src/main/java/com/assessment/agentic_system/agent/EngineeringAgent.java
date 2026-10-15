package com.assessment.agentic_system.agent;

/**
 * A specialised engineering agent. Agents recommend and produce; they never change workflow
 * state themselves. The engine decides what happens with each {@link AgentResult}.
 */
public interface EngineeringAgent {

    /** Unique agent type, matched against {@code TaskNode.agentType()}. See {@link AgentTypes}. */
    String type();

    AgentResult execute(AgentContext context);
}
