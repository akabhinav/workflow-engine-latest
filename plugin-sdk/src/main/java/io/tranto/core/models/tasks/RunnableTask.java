package io.tranto.core.models.tasks;

import io.tranto.core.models.Plugin;
import io.tranto.core.runners.RunContext;

/**
 * A task that does work. Its {@link #run(RunContext)} executes on a <em>worker</em>, off the
 * executor's hot path, and returns a typed {@link Output}.
 *
 * @param <T> the task's output type
 */
public interface RunnableTask<T extends Output> extends Plugin {

    /**
     * Do the work.
     *
     * @param runContext the runtime bridge (rendering, logging, storage, ...)
     * @return the task's output, or a {@link VoidOutput} when there is none
     * @throws Exception any failure — the worker records it and the task's state becomes FAILED
     */
    T run(RunContext runContext) throws Exception;
}
