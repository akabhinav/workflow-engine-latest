package io.tranto.core.models.flows;

import io.tranto.core.models.execution.StateType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The state of an execution or task-run: the {@link StateType#current current} value plus an
 * append-only history of transitions. Immutable — {@link #withState(StateType)} returns a new
 * {@code State} with one more history entry, which is what makes state changes replay-safe.
 *
 * @param current   the current state
 * @param histories the ordered transition history (oldest first)
 */
public record State(StateType current, List<History> histories) {

    /** One transition: the state entered and when. */
    public record History(StateType state, Instant date) {
    }

    public State {
        histories = histories == null ? List.of() : List.copyOf(histories);
    }

    /** @return a fresh state in {@link StateType#CREATED} with a single history entry. */
    public static State created() {
        return of(StateType.CREATED);
    }

    /** @return a fresh state in {@code type} with a single history entry (stamped now). */
    public static State of(final StateType type) {
        return new State(type, List.of(new History(type, Instant.now())));
    }

    /**
     * @return a new state transitioned to {@code next}, appending a history entry.
     */
    public State withState(final StateType next) {
        List<History> updated = new ArrayList<>(histories);
        updated.add(new History(next, Instant.now()));
        return new State(next, updated);
    }

    /** @return when this state first started (first history entry), or null if none. */
    public Instant startDate() {
        return histories.isEmpty() ? null : histories.get(0).date();
    }

    /** @return when this state reached its current value (last history entry), or null. */
    public Instant endDate() {
        return histories.isEmpty() ? null : histories.get(histories.size() - 1).date();
    }

    /** @return elapsed time between first and last transition. */
    public Duration duration() {
        Instant start = startDate();
        Instant end = endDate();
        return (start == null || end == null) ? Duration.ZERO : Duration.between(start, end);
    }

    public boolean isTerminated() {
        return current.isTerminated();
    }

    public boolean isRunning() {
        return current.isRunning();
    }
}
