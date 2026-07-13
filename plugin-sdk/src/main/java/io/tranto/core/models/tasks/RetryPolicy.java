package io.tranto.core.models.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Duration;
import java.util.Locale;

/**
 * Retry configuration for a task: a bounded attempt count plus a backoff policy that decides how
 * long to wait before each attempt.
 *
 * <ul>
 *   <li>{@link Behavior#CONSTANT} — wait {@code delay} before every retry.</li>
 *   <li>{@link Behavior#EXPONENTIAL} — wait {@code delay × 2^(attempt-1)}, capped at {@code maxDelay}.</li>
 * </ul>
 *
 * <p>{@code maxDuration} is an overall time budget: once the task-run has been retrying for longer
 * than this, no further attempt is made (the executor stops re-dispatching). All timing fields are
 * optional; the minimum useful policy is just {@code maxAttempts}.</p>
 *
 * @param maxAttempts total retry attempts allowed beyond the first (0 = no retry)
 * @param behavior    backoff shape (defaults to {@link Behavior#CONSTANT})
 * @param delay       base wait before a retry (null / zero = immediate)
 * @param maxDelay    upper bound on a computed exponential delay (null = uncapped)
 * @param maxDuration overall time budget for retrying; retries stop once exceeded (null = unbounded)
 */
public record RetryPolicy(int maxAttempts, Behavior behavior, Duration delay,
                          Duration maxDelay, Duration maxDuration) {

    /** How the delay grows across attempts. */
    public enum Behavior {
        /** Same {@code delay} before every attempt. */
        CONSTANT,
        /** {@code delay} doubles each attempt, capped at {@code maxDelay}. */
        EXPONENTIAL;

        /** Case-insensitive lookup so YAML can say {@code behavior: exponential}. */
        @JsonCreator
        public static Behavior fromString(final String value) {
            return value == null ? CONSTANT : Behavior.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public RetryPolicy {
        if (behavior == null) {
            behavior = Behavior.CONSTANT;
        }
    }

    /**
     * The wait before a given retry attempt.
     *
     * @param attempt the retry number being scheduled (1 = first retry)
     * @return the delay to apply before dispatching that attempt
     */
    public Duration delayForAttempt(final int attempt) {
        Duration base = delay == null ? Duration.ZERO : delay;
        Duration computed = base;
        if (behavior == Behavior.EXPONENTIAL && attempt > 1) {
            long factor = 1L << Math.min(attempt - 1, 30); // guard against overflow on the shift
            computed = base.multipliedBy(factor);
        }
        if (maxDelay != null && computed.compareTo(maxDelay) > 0) {
            computed = maxDelay;
        }
        return computed;
    }
}
