package io.tranto.plugin.scripts;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Runs an inline script through an interpreter (the platform shell by default; set {@code interpreter}
 * for python, bash, node, ...). The script body is Pebble-rendered, executed once, and its exit code,
 * captured stdout and stderr are exposed as outputs. Stdout/stderr are streamed line-by-line to the
 * task logger as the process runs.
 *
 * <pre>
 * - id: greet
 *   type: io.tranto.plugin.scripts.Script
 *   script: echo "hello {{ inputs.name }}"
 *
 * - id: transform
 *   type: io.tranto.plugin.scripts.Script
 *   interpreter: ["python3", "-c"]
 *   env:
 *     STAGE: "{{ inputs.stage }}"
 *   script: |
 *     import os
 *     print("stage:", os.environ["STAGE"])
 * </pre>
 *
 * <p>A non-zero exit fails the task by default (set {@code failOnNonZero: false} to tolerate it and
 * assert on {@code exitCode} downstream). Runs as a local OS process — the same wall-safe, pure-JDK
 * approach as the built-in Process runner; Docker/Kubernetes execution ships as separate runners.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run an inline script through an interpreter")
public class Script extends Task implements RunnableTask<Script.ScriptOutput> {

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    /** The inline script body. Pebble-rendered before execution. */
    @PluginProperty(dynamic = true)
    private String script;

    /**
     * The interpreter command tokens; the rendered script is appended as the final argument. Defaults
     * to the platform shell ({@code cmd.exe /c} on Windows, {@code sh -c} elsewhere).
     */
    @PluginProperty
    private List<String> interpreter;

    /** Extra environment variables for the process; values are Pebble-rendered. */
    @PluginProperty(dynamic = true)
    private Map<String, String> env;

    /** Optional working directory for the process. */
    @PluginProperty(dynamic = true)
    private String workingDir;

    /** Fail the task on a non-zero exit code (default true). */
    @PluginProperty
    private Boolean failOnNonZero;

    @Override
    public ScriptOutput run(final RunContext runContext) throws Exception {
        if (script == null || script.isBlank()) {
            return new ScriptOutput(0, List.of(), List.of());
        }
        String renderedScript = runContext.render(script);

        List<String> command = new ArrayList<>(interpreter != null && !interpreter.isEmpty()
            ? interpreter
            : (WINDOWS ? List.of("cmd.exe", "/c") : List.of("sh", "-c")));
        command.add(renderedScript);

        java.lang.ProcessBuilder builder = new java.lang.ProcessBuilder(command);
        builder.redirectErrorStream(false);
        if (workingDir != null && !workingDir.isBlank()) {
            builder.directory(new File(runContext.render(workingDir)));
        }
        if (env != null) {
            Map<String, String> renderedEnv = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : env.entrySet()) {
                renderedEnv.put(e.getKey(), runContext.render(e.getValue()));
            }
            builder.environment().putAll(renderedEnv);
        }

        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        java.lang.Process process = builder.start();
        CompletableFuture<Void> outPump = pump(process.getInputStream(), stdout,
            line -> runContext.logger().info(line));
        CompletableFuture<Void> errPump = pump(process.getErrorStream(), stderr,
            line -> runContext.logger().warn(line));

        int exitCode = process.waitFor();
        outPump.join();
        errPump.join();

        boolean failOnExit = failOnNonZero == null || failOnNonZero;
        if (failOnExit && exitCode != 0) {
            throw new RuntimeException("Script exited with non-zero code " + exitCode
                + (stderr.isEmpty() ? "" : ": " + String.join(" | ", stderr)));
        }
        return new ScriptOutput(exitCode, stdout, stderr);
    }

    /** Read a stream line-by-line on a virtual thread, collecting and logging each line. */
    private static CompletableFuture<Void> pump(final InputStream stream, final List<String> sink,
                                                final java.util.function.Consumer<String> log) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.add(line);
                    }
                    log.accept(line);
                }
            } catch (Exception e) {
                log.accept("stream read error: " + e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * @param exitCode the process exit code
     * @param stdout   captured standard-output lines
     * @param stderr   captured standard-error lines
     */
    public record ScriptOutput(int exitCode, List<String> stdout, List<String> stderr) implements Output {
    }
}
