package io.tranto.core.models.tasks;

import io.tranto.core.models.Plugin;
import io.tranto.core.runners.RunContext;

import java.util.List;

/**
 * A task that orchestrates other tasks (Sequential, Parallel, If, Switch, ...). Flowable tasks are
 * resolved by the <em>executor</em>, never sent to a worker.
 *
 * <p>The contract cleanly separates two concerns:</p>
 * <ul>
 *   <li><b>Which</b> children are active — {@link #activeChildren(RunContext)}. A plain container
 *       returns all of them; {@code If}/{@code Switch} render a condition and return only the
 *       selected branch.</li>
 *   <li><b>How</b> to run them — {@link #mode()}: one after another ({@link Mode#SEQUENTIAL}) or
 *       all at once ({@link Mode#PARALLEL}).</li>
 * </ul>
 * The executor drives the rest (nesting, state aggregation) generically from these two answers.
 *
 * @param <T> the flowable's output type
 */
public interface FlowableTask<T extends Output> extends Plugin {

    /** How a flowable runs its active children. */
    enum Mode {
        /** One child at a time, in order; stop on failure. */
        SEQUENTIAL,
        /** All active children concurrently. */
        PARALLEL
    }

    /** @return the execution mode for this flowable's active children. */
    default Mode mode() {
        return Mode.SEQUENTIAL;
    }

    /**
     * @param runContext the run context (so branching flowables can render a condition)
     * @return the children to actually run this time (all of them for a plain container; the
     *         selected branch/case for If/Switch)
     */
    List<Task> activeChildren(RunContext runContext) throws Exception;

    /**
     * Iteration values for a looping flowable (e.g. ForEach/Loop): the active children run once per
     * value, and each iteration's task-runs see the value via {@code {{ taskrun.value }}}.
     *
     * @return the values to iterate, or {@code null} for a non-iterating flowable (the default)
     */
    default List<String> iterationValues(RunContext runContext) throws Exception {
        return null;
    }

    /**
     * @return every declared child (across all branches), used for graph/topology and skipping.
     */
    List<Task> allChildren();
}
