package io.tranto.core.models.tasks.runners;

import java.util.List;

/**
 * The outcome of running a script task's commands.
 *
 * @param exitCode the process exit code (0 = success)
 * @param stdout   captured standard-output lines
 * @param stderr   captured standard-error lines
 */
public record RunnerResult(int exitCode, List<String> stdout, List<String> stderr) {
}
