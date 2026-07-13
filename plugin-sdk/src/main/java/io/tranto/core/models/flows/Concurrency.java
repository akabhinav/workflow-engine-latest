package io.tranto.core.models.flows;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Per-flow concurrency control: at most {@code limit} executions of a flow may run at once. When a
 * new execution would exceed the limit, {@code behavior} decides its fate.
 *
 * <ul>
 *   <li>{@link Behavior#QUEUE} — hold it in state {@code QUEUED}; admit it when a slot frees up.</li>
 *   <li>{@link Behavior#CANCEL} — terminate it immediately as {@code CANCELLED}.</li>
 *   <li>{@link Behavior#FAIL} — terminate it immediately as {@code FAILED}.</li>
 * </ul>
 *
 * @param limit    the maximum number of simultaneously-running executions (≤ 0 = no limit)
 * @param behavior what to do with an execution over the limit (defaults to {@link Behavior#QUEUE})
 */
public record Concurrency(int limit, Behavior behavior) {

    /** What happens to an execution that would exceed the concurrency limit. */
    public enum Behavior {
        /** Hold the execution QUEUED until a slot opens. */
        QUEUE,
        /** Cancel the execution immediately. */
        CANCEL,
        /** Fail the execution immediately. */
        FAIL;

        /** Case-insensitive lookup so YAML can say {@code behavior: cancel}. */
        @JsonCreator
        public static Behavior fromString(final String value) {
            return value == null ? QUEUE : Behavior.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public Concurrency {
        if (behavior == null) {
            behavior = Behavior.QUEUE;
        }
    }
}
