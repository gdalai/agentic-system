package com.assessment.agentic_system.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, 100, 2.0, Set.of("POLICY_DENIED"));

    @Test
    void retriesUpToMaxRetriesThenStops() {
        assertThat(policy.shouldRetry("TIMEOUT", 1)).isTrue();
        assertThat(policy.shouldRetry("TIMEOUT", 3)).isTrue();
        assertThat(policy.shouldRetry("TIMEOUT", 4)).isFalse();
        assertThat(policy.maxAttempts()).isEqualTo(4);
    }

    @Test
    void neverRetriesNonRetryableCodes() {
        assertThat(policy.shouldRetry("POLICY_DENIED", 1)).isFalse();
    }

    @Test
    void backoffGrowsExponentially() {
        assertThat(policy.backoffMs(1)).isEqualTo(100);
        assertThat(policy.backoffMs(2)).isEqualTo(200);
        assertThat(policy.backoffMs(3)).isEqualTo(400);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryPolicy(-1, 0, 1.0, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 0, 0.5, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
