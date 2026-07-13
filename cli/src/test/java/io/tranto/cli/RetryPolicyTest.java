package io.tranto.cli;

import io.tranto.core.models.tasks.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for the retry backoff computation ({@link RetryPolicy#delayForAttempt(int)}). */
class RetryPolicyTest {

    @Test
    void constantDelayIsFlatAcrossAttempts() {
        RetryPolicy policy = new RetryPolicy(3, RetryPolicy.Behavior.CONSTANT,
            Duration.ofSeconds(2), null, null);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void exponentialDoublesEachAttemptAndCapsAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(5, RetryPolicy.Behavior.EXPONENTIAL,
            Duration.ofSeconds(1), Duration.ofSeconds(5), null);
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(1)); // 1 * 2^0
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(2)); // 1 * 2^1
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(4)); // 1 * 2^2
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(5)); // capped at maxDelay
    }

    @Test
    void behaviorDefaultsToConstantWhenNull() {
        RetryPolicy policy = new RetryPolicy(1, null, Duration.ofSeconds(1), null, null);
        assertThat(policy.behavior()).isEqualTo(RetryPolicy.Behavior.CONSTANT);
    }
}
