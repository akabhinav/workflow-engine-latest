package io.tranto.core.models.tasks.runners;

import io.tranto.core.models.Plugin;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Where a script task's commands actually run: a local OS process, a Docker container, a Kubernetes
 * pod, etc. Script tasks are written once against this abstraction and stay portable — swapping the
 * runner changes <em>where</em> the commands execute, not the task.
 *
 * <p>A polymorphic plugin (resolved by {@code type}) like Task and AbstractTrigger, so a flow can
 * pick a runner in YAML: {@code taskRunner: { type: io.tranto.plugin.core.runner.Process }}.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class TaskRunner implements Plugin {

    /** The plugin type identifier of this runner. */
    protected String type;

    /**
     * Execute the (already-rendered) commands and return the aggregated result.
     *
     * @param runContext the run context (for logging and rendering)
     * @param commands   the shell commands to run, in order
     * @return the exit code and captured output
     */
    public abstract RunnerResult run(RunContext runContext, List<String> commands) throws Exception;
}
