package io.tranto.core.models.triggers;

import java.time.ZonedDateTime;

/**
 * A time-based trigger: given a moment, it can say when it should next fire. The scheduler polls
 * this to decide when to create an execution — it never blocks a thread waiting; it just compares
 * "now" against {@link #nextEvaluationDate(ZonedDateTime)}.
 */
public interface Schedulable {

    /**
     * @param after the moment to compute the next fire time strictly after
     * @return the next instant this trigger should fire, or {@code null} if it never will again
     */
    ZonedDateTime nextEvaluationDate(ZonedDateTime after);
}
