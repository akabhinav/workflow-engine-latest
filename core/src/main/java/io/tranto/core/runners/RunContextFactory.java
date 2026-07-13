package io.tranto.core.runners;

import io.tranto.core.kv.KVStoreFactory;
import io.tranto.core.kv.MemoryKVStore;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.storages.LocalStorage;
import io.tranto.core.storages.StorageInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@link RunContext}s. Central place that assembles the variable map a task sees
 * (inputs, flow info, execution info, task info) so the shape is consistent everywhere.
 */
public class RunContextFactory {

    private final VariableRenderer renderer;
    private final StorageInterface storage;
    private final KVStoreFactory kvFactory;

    public RunContextFactory(final VariableRenderer renderer,
                             final StorageInterface storage,
                             final KVStoreFactory kvFactory) {
        this.renderer = renderer;
        this.storage = storage;
        this.kvFactory = kvFactory;
    }

    public RunContextFactory(final VariableRenderer renderer) {
        this(renderer, defaultStorage(), new MemoryKVStore.Factory());
    }

    public RunContextFactory() {
        this(new VariableRenderer());
    }

    private static StorageInterface defaultStorage() {
        return new LocalStorage(Path.of(System.getProperty("java.io.tmpdir"), "tranto-storage"));
    }

    /** @return the storage backing this factory's run contexts. */
    public StorageInterface storage() {
        return storage;
    }

    /** Build a context from an explicit variable map (used by tests and low-level callers). */
    public RunContext of(final Map<String, Object> variables) {
        return new DefaultRunContext(renderer, variables, LoggerFactory.getLogger("flow"),
            storage, kvFactory.forNamespace(""));
    }

    /**
     * Build the context for a specific task-run: assembles {@code inputs}, {@code flow},
     * {@code execution} and {@code taskrun} variable groups.
     */
    public RunContext forTask(final Flow flow,
                              final Execution execution,
                              final Task task,
                              final TaskRun taskRun) {
        Map<String, Object> vars = baseVariables(flow, execution);

        Map<String, Object> taskInfo = new LinkedHashMap<>();
        taskInfo.put("id", task.getId());
        taskInfo.put("type", task.getType());
        vars.put("task", taskInfo);

        Map<String, Object> taskRunInfo = new LinkedHashMap<>();
        taskRunInfo.put("id", taskRun.getId());
        taskRunInfo.put("value", taskRun.getValue());
        vars.put("taskrun", taskRunInfo);

        Logger logger = LoggerFactory.getLogger(flow.getNamespace() + "." + flow.getId());
        return new DefaultRunContext(renderer, vars, logger, storage, kvFactory.forNamespace(flow.getNamespace()));
    }

    /** The variable groups common to every task in an execution. */
    public Map<String, Object> baseVariables(final Flow flow, final Execution execution) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("inputs", execution.getInputs() == null ? Map.of() : execution.getInputs());

        Map<String, Object> flowInfo = new LinkedHashMap<>();
        flowInfo.put("id", flow.getId());
        flowInfo.put("namespace", flow.getNamespace());
        flowInfo.put("revision", flow.getRevision());
        vars.put("flow", flowInfo);

        Map<String, Object> executionInfo = new LinkedHashMap<>();
        executionInfo.put("id", execution.getId());
        vars.put("execution", executionInfo);

        // Task outputs by task id, so expressions can read {{ outputs.<taskId>.<key> }}.
        Map<String, Object> outputsByTask = new LinkedHashMap<>();
        if (execution.getTaskRunList() != null) {
            for (TaskRun taskRun : execution.getTaskRunList()) {
                if (taskRun.getOutputs() != null) {
                    outputsByTask.put(taskRun.getTaskId(), taskRun.getOutputs());
                }
            }
        }
        vars.put("outputs", outputsByTask);
        return vars;
    }
}
