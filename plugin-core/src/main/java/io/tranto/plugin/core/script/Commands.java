package io.tranto.plugin.core.script;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.runners.RunnerResult;
import io.tranto.core.models.tasks.runners.TaskRunner;
import io.tranto.core.runners.RunContext;
import io.tranto.plugin.core.runner.Process;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a list of shell commands via a {@link TaskRunner} (a local process by default). Each command
 * is rendered, then executed in order; a non-zero exit fails the task. The exit code and captured
 * stdout are exposed as outputs for downstream tasks.
 *
 * <pre>
 * - id: build
 *   type: io.tranto.plugin.core.script.Commands
 *   commands:
 *     - echo "building {{ inputs.name }}"
 *     - ./gradlew build
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run shell commands")
public class Commands extends Task implements RunnableTask<Commands.CommandsOutput> {

    /** The shell commands to run, in order. Each is Pebble-rendered before execution. */
    @PluginProperty(dynamic = true)
    private List<String> commands;

    /** Where the commands run; defaults to a local {@link Process} runner. */
    private TaskRunner taskRunner;

    @Override
    public CommandsOutput run(final RunContext runContext) throws Exception {
        if (commands == null || commands.isEmpty()) {
            return new CommandsOutput(0, List.of());
        }
        List<String> rendered = new ArrayList<>(commands.size());
        for (String command : commands) {
            rendered.add(runContext.render(command));
        }

        TaskRunner runner = taskRunner != null ? taskRunner : Process.builder().build();
        RunnerResult result = runner.run(runContext, rendered);

        if (result.exitCode() != 0) {
            throw new RuntimeException("Command exited with non-zero code " + result.exitCode());
        }
        return new CommandsOutput(result.exitCode(), result.stdout());
    }

    /** @param exitCode process exit code; @param stdout captured standard-output lines. */
    public record CommandsOutput(int exitCode, List<String> stdout) implements Output {
    }
}
