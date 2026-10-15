package com.assessment.agentic_system.ai;

/**
 * Minimal model-provider contract. Agents never call a provider directly; they go through a
 * component (such as {@link RequirementAnalyzer}) that owns the prompt, parses the reply and
 * validates it. The engine only ever acts on validated, structured output.
 */
public interface LlmClient {

    /** Provider and model identifier, recorded in agent outputs for traceability. */
    String name();

    /**
     * @throws LlmUnavailableException when the provider cannot be reached or returns an error.
     *         Agents map this to a retryable failure.
     */
    String complete(String systemPrompt, String userPrompt);

    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
