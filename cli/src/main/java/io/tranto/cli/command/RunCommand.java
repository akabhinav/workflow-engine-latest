package io.tranto.cli.command;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginClassLoader;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.PluginScanner;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code tranto run <flow.yaml>} — parse a flow file and run it to completion in the all-in-one
 * engine, printing the execution outcome and per-task states.
 *
 * <p>Inputs are passed with {@code -i key=value} (repeatable); subflow children are pre-registered
 * with {@code --register other.yaml}; external plugin JARs are loaded from {@code --plugins <dir>}
 * (each isolated in a child-first {@link PluginClassLoader} and discovered via ServiceLoader).</p>
 */
@Command(name = "run", description = "Run a flow file to completion.")
public class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to a flow YAML file.")
    private Path flowFile;

    @Option(names = {"-i", "--input"}, description = "Flow input as key=value (repeatable).")
    private Map<String, String> inputs = new LinkedHashMap<>();

    @Option(names = {"--register"}, description = "Additional flow file(s) to register first (e.g. subflows).")
    private List<Path> register = new ArrayList<>();

    @Option(names = {"--plugins"}, description = "Directory of external plugin JARs to load and discover.")
    private Path pluginsDir;

    @Option(names = {"-t", "--timeout"}, description = "Max seconds to wait (default 60).", defaultValue = "60")
    private long timeoutSeconds;

    @Override
    public Integer call() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();

        // Built-in plugins.
        CorePlugins.all().forEach(registry::register);

        // External plugin JARs (isolated classloader + ServiceLoader discovery).
        int external = 0;
        if (pluginsDir != null && Files.isDirectory(pluginsDir)) {
            List<URL> jars = new ArrayList<>();
            try (Stream<Path> files = Files.list(pluginsDir)) {
                for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    jars.add(jar.toUri().toURL());
                }
            }
            if (!jars.isEmpty()) {
                PluginClassLoader loader = new PluginClassLoader(
                    jars.toArray(URL[]::new), Thread.currentThread().getContextClassLoader());
                var found = new PluginScanner().scan(loader);
                found.forEach(registry::register);
                // Count only plugins actually defined by the external JARs (not built-ins re-scanned via parent).
                external = (int) found.stream().filter(c -> c.getClassLoader() == loader).count();
            }
        }

        YamlFlowParser parser = new YamlFlowParser(new JacksonMapper(registry));
        Flow flow = parser.parse(Files.readString(flowFile));

        try (StandaloneEngine engine = new StandaloneEngine()) {
            for (Path dependency : register) {
                engine.register(parser.parse(Files.readString(dependency)));
            }

            Map<String, Object> flowInputs = new LinkedHashMap<>(inputs);
            Execution execution = engine.run(flow, flowInputs, Duration.ofSeconds(timeoutSeconds));

            System.out.println();
            if (external > 0) {
                System.out.println("Plugins   : " + external + " external plugin(s) loaded from " + pluginsDir);
            }
            System.out.println("Flow      : " + flow.getNamespace() + "." + flow.getId());
            System.out.println("Execution : " + execution.getId());
            System.out.println("State     : " + execution.getState().current());
            execution.getTaskRunList().forEach(taskRun ->
                System.out.println("   - " + taskRun.getTaskId() + " -> " + taskRun.getState().current()));
            if (execution.getOutputs() != null && !execution.getOutputs().isEmpty()) {
                System.out.println("Outputs   : " + execution.getOutputs());
            }

            return execution.getState().current() == StateType.SUCCESS ? 0 : 1;
        }
    }
}
