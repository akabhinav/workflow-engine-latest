package io.tranto.core.models.execution;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The lifecycle state of an {@code Execution} or a {@code TaskRun}.
 *
 * <p>This enum is intentionally placed in the {@code model} module (rather than nested
 * inside a {@code State} class as Kestra does) so that queues, repositories, plugins and
 * the engine can all reference the canonical state values without importing the richer
 * {@code State} type. Predicate helpers ({@link #isTerminated()} etc.) keep state logic in
 * one place instead of being re-derived across the codebase.</p>
 */
public enum StateType {
    /** Freshly created, not yet started. */
    CREATED,
    /** Handed to the queue for a worker to pick up. */
    SUBMITTED,
    /** Actively running. */
    RUNNING,
    /** Paused, awaiting a manual resume or a delay. */
    PAUSED,
    /** Restarted after a previous terminal state. */
    RESTARTED,
    /** A kill has been requested; still winding down. */
    KILLING,
    /** Finished successfully. */
    SUCCESS,
    /** Finished successfully but with a non-fatal warning. */
    WARNING,
    /** Finished with an error. */
    FAILED,
    /** Killed on request. */
    KILLED,
    /** Cancelled before completing (e.g. concurrency limit). */
    CANCELLED,
    /** Held back by a concurrency limit, waiting for a slot. */
    QUEUED,
    /** A retry is pending. */
    RETRYING,
    /** Superseded by a retry attempt. */
    RETRIED,
    /** Deliberately skipped. */
    SKIPPED,
    /** Stopped at a debugging breakpoint. */
    BREAKPOINT,
    /** Re-submitted after recovery. */
    RESUBMITTED,
    /** Unrecognised value (forward-compatible deserialization). */
    UNKNOWN;

    /**
     * Case-insensitive lookup used by Jackson; unknown strings map to {@link #UNKNOWN}
     * so a newer producer never breaks an older consumer.
     */
    @JsonCreator
    public static StateType fromString(final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return StateType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** @return true if this is a final state that will not transition further. */
    public boolean isTerminated() {
        return switch (this) {
            case SUCCESS, WARNING, FAILED, KILLED, CANCELLED, RETRIED, SKIPPED, RESUBMITTED -> true;
            default -> false;
        };
    }

    /** @return true if terminated without an error (success-like). */
    public boolean isTerminatedNoFail() {
        return switch (this) {
            case SUCCESS, WARNING, KILLED, CANCELLED, SKIPPED -> true;
            default -> false;
        };
    }

    /** @return true if terminated in an error state. */
    public boolean isFailed() {
        return this == FAILED;
    }

    /** @return true if actively running or being killed. */
    public boolean isRunning() {
        return this == RUNNING || this == KILLING;
    }

    /** @return true if created or restarted (i.e. eligible to start). */
    public boolean isCreated() {
        return this == CREATED || this == RESTARTED;
    }

    /** @return true if paused. */
    public boolean isPaused() {
        return this == PAUSED;
    }
}
