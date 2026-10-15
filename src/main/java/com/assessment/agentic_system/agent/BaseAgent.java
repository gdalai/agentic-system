package com.assessment.agentic_system.agent;

/** Shared behaviour for the deterministic agents: injected failures, then the agent's own work. */
public abstract class BaseAgent implements EngineeringAgent {

    private final FaultInjector faultInjector;

    protected BaseAgent(FaultInjector faultInjector) {
        this.faultInjector = faultInjector;
    }

    @Override
    public final AgentResult execute(AgentContext context) {
        if (faultInjector != null && faultInjector.shouldFail(type())) {
            return AgentResult.failure("INJECTED_FAILURE", type() + " agent failed (injected)");
        }
        return doExecute(context);
    }

    protected abstract AgentResult doExecute(AgentContext context);
}
