package com.assessment.agentic_system.orchestration;

/** How much the engine may do without a human decision. Enforced by the policy engine (next step). */
public enum AutonomyLevel {
    /** Every task needs approval before it runs. */
    MANUAL,
    /** Only tasks listed in approvalRequiredFor need approval. */
    CONTROLLED,
    /** No approvals; still bounded by policy, retries and validation. */
    AUTONOMOUS
}
