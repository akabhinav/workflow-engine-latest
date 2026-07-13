package io.tranto.core.runners;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.executions.NextTaskRun;
import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.tasks.FlowableTask;
import io.tranto.core.models.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves what happens next in an execution by walking the flow's task tree against the current
 * task-run state. Handles arbitrary nesting of flowable tasks (Sequential/Parallel/If/Switch),
 * iteration (Loop/ForEach), and the three execution phases (main -> errors -> finally).
 *
 * <p>Pure function over the immutable {@link Execution}: never mutates in place; returns an updated
 * execution carrying any newly-created (flowable RUNNING / leaf SUBMITTED) task-runs. Outcomes are
 * expressed as a {@link Resolution}: leaf runs to dispatch, a terminal state, or "waiting".</p>
 */
public class ExecutionPlanner {

    private final RunContextFactory runContextFactory;
    private final Flow flow;

    public ExecutionPlanner(final RunContextFactory runContextFactory, final Flow flow) {
        this.runContextFactory = runContextFactory;
        this.flow = flow;
    }

    /** The outcome of planning a (sub)tree: nexts to dispatch, a terminal state, paused, or waiting. */
    public record Resolution(Execution execution, List<NextTaskRun> nexts, StateType terminal, boolean paused) {

        static Resolution nexts(final Execution execution, final List<NextTaskRun> nexts) {
            return new Resolution(execution, nexts, null, false);
        }

        static Resolution terminal(final Execution execution, final StateType terminal) {
            return new Resolution(execution, List.of(), terminal, false);
        }

        static Resolution waiting(final Execution execution) {
            return new Resolution(execution, List.of(), null, false);
        }

        static Resolution paused(final Execution execution) {
            return new Resolution(execution, List.of(), null, true);
        }

        public boolean hasNexts() {
            return !nexts.isEmpty();
        }

        public boolean isTerminal() {
            return terminal != null;
        }

        public boolean isPaused() {
            return paused;
        }
    }

    /**
     * Plan the whole execution as three phases: the main task list, then (only if main failed) the
     * error-handler tasks, then the finally tasks (always). The main outcome dominates.
     */
    public Resolution plan(final Execution execution) {
        Resolution main = resolveSequence(flow.getTasks(), execution, null, null);
        if (!main.isTerminal()) {
            return main;
        }
        StateType mainState = main.terminal();
        Execution work = main.execution();

        if (mainState == StateType.FAILED && notEmpty(flow.getErrors())) {
            Resolution errors = resolveSequence(flow.getErrors(), work, "$errors", null);
            if (!errors.isTerminal()) {
                return errors;
            }
            work = errors.execution();
        }

        if (notEmpty(flow.getFinally())) {
            Resolution fin = resolveSequence(flow.getFinally(), work, "$finally", null);
            if (!fin.isTerminal()) {
                return fin;
            }
            work = fin.execution();
        }

        return Resolution.terminal(work, mainState);
    }

    /** Run a list of tasks one after another; stop at the first not-yet-done or failed task. */
    private Resolution resolveSequence(final List<Task> tasks, final Execution execution,
                                       final String parentId, final String value) {
        Execution work = execution;
        for (Task task : tasks) {
            Optional<TaskRun> runOpt = work.findTaskRun(task.getId(), parentId);

            if (runOpt.isEmpty()) {
                Resolution started = startTask(task, work, parentId, value);
                work = started.execution();
                if (started.isTerminal()) {
                    if (started.terminal() == StateType.FAILED) {
                        return Resolution.terminal(work, StateType.FAILED);
                    }
                    continue;
                }
                return started;
            }

            TaskRun run = runOpt.get();
            StateType current = run.getState().current();
            if (current == StateType.FAILED) {
                return Resolution.terminal(work, StateType.FAILED);
            }
            if (!run.isTerminated()) {
                if (task instanceof FlowableTask<?> flowable) {
                    Resolution inner = resolveFlowable(flowable, work, run, value);
                    work = inner.execution();
                    if (inner.isTerminal()) {
                        work = work.withTaskRun(run.withState(inner.terminal()));
                        if (inner.terminal() == StateType.FAILED) {
                            return Resolution.terminal(work, StateType.FAILED);
                        }
                        continue;
                    }
                    return inner;
                }
                return Resolution.waiting(work);
            }
        }
        return Resolution.terminal(work, StateType.SUCCESS);
    }

    /** Run all active children concurrently; done when all are terminal. */
    private Resolution resolveParallel(final List<Task> children, final Execution execution,
                                       final String parentId, final String value) {
        Execution work = execution;
        List<NextTaskRun> nexts = new ArrayList<>();

        for (Task child : children) {
            Optional<TaskRun> runOpt = work.findTaskRun(child.getId(), parentId);
            if (runOpt.isEmpty()) {
                Resolution started = startTask(child, work, parentId, value);
                work = started.execution();
                nexts.addAll(started.nexts());
            } else {
                TaskRun run = runOpt.get();
                if (!run.isTerminated() && child instanceof FlowableTask<?> flowable) {
                    Resolution inner = resolveFlowable(flowable, work, run, value);
                    work = inner.execution();
                    if (inner.isTerminal()) {
                        work = work.withTaskRun(run.withState(inner.terminal()));
                    } else {
                        nexts.addAll(inner.nexts());
                    }
                }
            }
        }

        return completion(children, work, parentId, nexts);
    }

    /** Start a not-yet-run task: pause, dispatch a leaf, or open a flowable and resolve inside it. */
    private Resolution startTask(final Task task, final Execution execution,
                                 final String parentId, final String value) {
        if (task instanceof io.tranto.core.models.tasks.Pausable) {
            TaskRun pauseRun = TaskRun.of(execution.getId(), task.getId(), parentId)
                .toBuilder().value(value).build()
                .withState(StateType.PAUSED);
            return Resolution.paused(execution.withTaskRun(pauseRun));
        }
        if (task instanceof FlowableTask<?> flowable) {
            TaskRun flowableRun = TaskRun.of(execution.getId(), task.getId(), parentId)
                .toBuilder().value(value).build()
                .withState(StateType.RUNNING);
            Execution opened = execution.withTaskRun(flowableRun);

            Resolution inner = resolveFlowable(flowable, opened, flowableRun, value);
            if (inner.isTerminal()) {
                Execution done = inner.execution().withTaskRun(flowableRun.withState(inner.terminal()));
                return Resolution.terminal(done, inner.terminal());
            }
            return inner;
        }

        TaskRun leafRun = TaskRun.of(execution.getId(), task.getId(), parentId)
            .toBuilder().value(value).build()
            .withState(StateType.SUBMITTED);
        Execution updated = execution.withTaskRun(leafRun);
        return Resolution.nexts(updated, List.of(new NextTaskRun(task, leafRun)));
    }

    /** Resolve a flowable's children: iterate them per value (Loop) or run them once. */
    private Resolution resolveFlowable(final FlowableTask<?> flowable, final Execution execution,
                                       final TaskRun flowableRun, final String inheritedValue) {
        List<Task> active;
        List<String> iterations;
        try {
            RunContext runContext = runContextFactory.forTask(flow, execution, (Task) flowable, flowableRun);
            iterations = flowable.iterationValues(runContext);
            active = flowable.activeChildren(runContext);
        } catch (Exception e) {
            return Resolution.terminal(execution, StateType.FAILED);
        }
        if (active == null || active.isEmpty()) {
            return Resolution.terminal(execution, StateType.SUCCESS);
        }

        if (iterations == null) {
            return flowable.mode() == FlowableTask.Mode.PARALLEL
                ? resolveParallel(active, execution, flowableRun.getId(), inheritedValue)
                : resolveSequence(active, execution, flowableRun.getId(), inheritedValue);
        }
        return resolveIterations(flowable, active, iterations, execution, flowableRun);
    }

    /** Run the active children once per iteration value, under a per-value scope. */
    private Resolution resolveIterations(final FlowableTask<?> flowable, final List<Task> active,
                                         final List<String> values, final Execution execution,
                                         final TaskRun flowableRun) {
        boolean parallel = flowable.mode() == FlowableTask.Mode.PARALLEL;
        Execution work = execution;
        List<NextTaskRun> nexts = new ArrayList<>();

        for (String value : values) {
            String scope = flowableRun.getId() + "#" + value;
            Resolution iter = resolveSequence(active, work, scope, value);
            work = iter.execution();

            if (iter.terminal() == StateType.FAILED) {
                return Resolution.terminal(work, StateType.FAILED);
            }
            if (iter.hasNexts()) {
                nexts.addAll(iter.nexts());
                if (!parallel) {
                    return Resolution.nexts(work, nexts); // sequential: one iteration at a time
                }
            } else if (!iter.isTerminal() && !parallel) {
                return Resolution.waiting(work); // sequential: this iteration still running
            }
        }

        if (!nexts.isEmpty()) {
            return Resolution.nexts(work, nexts);
        }
        // All iterations dispatched: complete when every iteration's children are terminal.
        final Execution scan = work;
        boolean anyFailed = false;
        boolean allDone = true;
        for (String value : values) {
            String scope = flowableRun.getId() + "#" + value;
            for (Task child : active) {
                Optional<TaskRun> r = scan.findTaskRun(child.getId(), scope);
                if (r.isEmpty() || !r.get().isTerminated()) {
                    allDone = false;
                } else if (r.get().getState().current() == StateType.FAILED) {
                    anyFailed = true;
                }
            }
        }
        if (anyFailed) {
            return Resolution.terminal(work, StateType.FAILED);
        }
        return allDone ? Resolution.terminal(work, StateType.SUCCESS) : Resolution.waiting(work);
    }

    /** Decide a parallel group's completion by scanning its children's current runs. */
    private Resolution completion(final List<Task> children, final Execution work,
                                  final String parentId, final List<NextTaskRun> nexts) {
        if (!nexts.isEmpty()) {
            return Resolution.nexts(work, nexts);
        }
        boolean anyFailed = children.stream().anyMatch(c ->
            work.findTaskRun(c.getId(), parentId)
                .map(r -> r.getState().current() == StateType.FAILED).orElse(false));
        if (anyFailed) {
            return Resolution.terminal(work, StateType.FAILED);
        }
        boolean allDone = children.stream().allMatch(c ->
            work.findTaskRun(c.getId(), parentId).map(TaskRun::isTerminated).orElse(false));
        return allDone ? Resolution.terminal(work, StateType.SUCCESS) : Resolution.waiting(work);
    }

    private static boolean notEmpty(final List<Task> tasks) {
        return tasks != null && !tasks.isEmpty();
    }
}
