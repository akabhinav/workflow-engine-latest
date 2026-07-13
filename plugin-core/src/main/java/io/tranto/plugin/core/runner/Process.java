package io.tranto.plugin.core.runner;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.tasks.runners.RunnerResult;
import io.tranto.core.models.tasks.runners.TaskRunner;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs script commands as local OS processes — the default {@link TaskRunner}. Each command is
 * executed through the platform shell ({@code cmd /c} on Windows, {@code sh -c} elsewhere),
 * sequentially, stopping at the first non-zero exit. Stdout/stderr are streamed to the task logger
 * and captured into the {@link RunnerResult}.
 *
 * <p>Docker/Kubernetes runners are additional {@code TaskRunner} plugins with the same contract;
 * they need their respective daemons and so ship separately.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run script commands as local OS processes")
public class Process extends TaskRunner {

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    @Override
    public RunnerResult run(final RunContext runContext, final List<String> commands) throws Exception {
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        int lastExit = 0;

        for (String command : commands) {
            java.lang.ProcessBuilder builder = WINDOWS
                ? new java.lang.ProcessBuilder("cmd.exe", "/c", command)
                : new java.lang.ProcessBuilder("sh", "-c", command);
            builder.redirectErrorStream(false);
            java.lang.Process process = builder.start();

            CompletableFuture<Void> outPump = pump(process.getInputStream(), stdout,
                line -> runContext.logger().info(line));
            CompletableFuture<Void> errPump = pump(process.getErrorStream(), stderr,
                line -> runContext.logger().warn(line));

            lastExit = process.waitFor();
            outPump.join();
            errPump.join();

            if (lastExit != 0) {
                break; // fail fast, like `set -e`
            }
        }
        return new RunnerResult(lastExit, stdout, stderr);
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
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }
}
