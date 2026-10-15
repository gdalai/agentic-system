package com.assessment.agentic_system.execution;

import java.util.Set;

/**
 * Bounded retry with exponential backoff. Only failures whose error code is retryable are retried;
 * policy and validation failures are not, because repeating them cannot change the outcome.
 *
 * @param maxRetries         retries after the first attempt (so max attempts = maxRetries + 1)
 * @param initialBackoffMs   delay before the first retry
 * @param multiplier         backoff growth per retry
 * @param nonRetryableCodes  error codes that fail immediately
 */
public record RetryPolicy(int maxRetries, long initialBackoffMs, double multiplier, Set<String> nonRetryableCodes) {

    public static final Set<String> DEFAULT_NON_RETRYABLE =
            Set.of("POLICY_DENIED", "VALIDATION_FAILED", "NULL_RESULT", "IMPACT_UNKNOWN");

    public RetryPolicy {
        if (maxRetries < 0 || initialBackoffMs < 0 || multiplier < 1.0) {
            throw new IllegalArgumentException("Invalid retry policy");
        }
        nonRetryableCodes = nonRetryableCodes == null ? Set.of() : Set.copyOf(nonRetryableCodes);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(0, 0, 1.0, DEFAULT_NON_RETRYABLE);
    }

    /** @param attemptsSoFar number of attempts already made, including the one that just failed */
    public boolean shouldRetry(String errorCode, int attemptsSoFar) {
        return attemptsSoFar <= maxRetries && !nonRetryableCodes.contains(errorCode);
    }

    /** Backoff before retry number {@code retry} (1-based). */
    public long backoffMs(int retry) {
        return Math.round(initialBackoffMs * Math.pow(multiplier, Math.max(0, retry - 1)));
    }

    public int maxAttempts() {
        return maxRetries + 1;
    }
}
