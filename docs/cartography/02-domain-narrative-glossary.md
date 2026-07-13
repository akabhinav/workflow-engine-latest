# Domain Narrative — Tranto

**This system exists to** run **declarative workflows** — ordered, branching, retrying graphs of
tasks described in YAML — reliably and observably, without the author writing any orchestration
code. It is a ground-up Java/Spring reimplementation of the Kestra workflow engine, built to prove
that the hard core of such an engine (a durable state machine over a message queue, plus a stable
plugin contract) can be rebuilt cleanly and run both in-process and as a coordinated cluster.

**It serves:**
- **Flow authors / data & platform engineers** — write a `Flow` in YAML (tasks, inputs, triggers, error handling) and get it executed, retried, scheduled, and observed. Goal: automate a pipeline without owning a scheduler.
- **Operators** — start (`tranto run`), deploy nodes (`tranto server`), trigger/poll/kill/resume executions over REST, watch progress via SSE and `/metrics`. Goal: run and control workflows in production.
- **Plugin authors (1st- and 3rd-party)** — build new task/trigger types against a semver-stable SDK, with the guarantee (enforced by an ArchUnit test) that plugins cannot see engine internals. Goal: extend the system without forking it.
- **The scheduler & other flows (non-human actors)** — cron schedules and upstream flow completions push work in automatically.

**Core domain concepts (7):**
- **Flow** — the reusable *definition* of a workflow (identity: tenant + namespace + id + revision).
- **Task** — one step in a flow; either a `RunnableTask` (does leaf work on a worker) or a `FlowableTask` (orchestrates child tasks: If/Switch/Parallel/Sequential/Loop/Subflow/Pause).
- **Execution** — one *run* of a flow (immutable; identity: a UUID).
- **TaskRun** — one run of one task within an execution (immutable; may nest under a parent TaskRun).
- **State / StateType** — the 18-value lifecycle enum + append-only history that *is* the crystallized domain model.
- **Trigger** — what starts an execution without a human: a cron `Schedule` or a `FlowTrigger` (fires when another flow finishes).
- **Plugin** — the extension unit; every task, trigger, and runner is a `Plugin` resolved polymorphically by its `type:` string.

**The central process is:** an author registers a Flow → a trigger, an API call, or the CLI creates
an **Execution** → the **Executor** repeatedly runs the **ExecutionPlanner** (a pure function) over
the immutable execution to decide the *next* task-runs → leaf task-runs are dispatched as messages to
a **Worker**, which runs them and returns results → the Executor joins each result back into a new
immutable Execution and re-plans → this loop continues, honouring branches, loops, retries, pauses,
concurrency limits and error/finally hooks, until the execution reaches a **terminal state**, at
which point flow outputs are rendered and downstream flow-triggers fire.

**It is constrained by:**
- **Correctness-under-concurrency:** the whole design bets on **immutable Execution/TaskRun + a per-execution `ReentrantLock` + idempotent "apply this task-run delta"** so that at-least-once message delivery is safe (`Executor.java:20-27`, `Execution.withTaskRun` at `Execution.java:83-94`). This is the non-negotiable invariant.
- **The "stability wall":** plugins compile against `plugin-sdk` only (`provided` scope) and an ArchUnit test (`ArchitectureTest`) fails the build if a plugin references engine internals. This is a *self-imposed contractual* constraint borrowed from Kestra's plugin-ecosystem model (docs/08).
- **Transport-agnosticism:** engine code may touch state *only* through `QueueInterface` + repository interfaces, so the same classes run standalone or distributed. `DistributedEngineTest` is the tripwire that keeps this honest.
- **Kestra compatibility of concepts** (not code): the vocabulary, state machine, and YAML shape deliberately mirror Kestra so the mental model transfers.

**It grew by** (reconstructed from `docs/PROGRESS.md`/`STATUS.md` phase markers — **no git history
available to confirm**): a phased build — (Phase 1-2) model + serde + templating; (Phase 3) the
sequential execution spine — queue/executor/worker; (Phase 4) DAG control flow, retries, pause/kill,
concurrency; then scheduler + cron + flow triggers; then ServiceLoader plugin discovery; then JDBC
persistence + queue; then the distributed engine + CLI server; then REST/SSE/metrics. The engine
core and platform are described as "built + verified green"; what remains is breadth (a Vue UI,
hundreds of integration plugins) and production-hardening of the distributed layer.

**Overall confidence:** **high** for structure, vocabulary, and flow; **medium** on evolution
history (no VCS) and on which "distributed/production" pieces are real vs aspirational (several
interfaces document a second implementation that does not yet exist — see the Landmine Register).
Biggest open questions: is the JDBC queue safe under node failure? are KV/storage/concurrency
correct in a real multi-node deployment? (both are **No / Not-yet** per the code — see Open Questions).

---

## Ubiquitous Language Glossary

The vocabulary *is* the domain. Harvested from class names, enum values, field names, annotations,
topic strings, and table names.

| Term | Meaning (inferred) | Also called | Evidence | Confidence |
|------|--------------------|-------------|----------|------------|
| **Flow** | The declarative workflow definition authored in YAML | "flow definition" | `plugin-sdk .../flows/Flow.java` | High |
| **Namespace** | Dotted grouping/ownership scope for flows (e.g. `company.users`, `finance`) | | `Flow.java:37`; examples | High |
| **Revision** | Monotonic version of a flow | | `Flow.java:40` (stored but not used in lookup — Landmine #5) | High |
| **Tenant / tenantId** | Multi-tenancy key (currently always null/`""`) | | `Flow.java:82`, `JdbcDatabase.java:57` | High |
| **Execution** | One run of a flow; immutable, UUID-identified | "run" | `.../executions/Execution.java` | High |
| **TaskRun** | One run of one task inside an execution; immutable, nestable | | `.../executions/TaskRun.java` | High |
| **NextTaskRun** | Executor's decision to start a specific (task, taskRun) next | | `.../executions/NextTaskRun.java` | High |
| **Task** | A step in a flow; base of all plugins-that-do-work | | `.../tasks/Task.java` | High |
| **RunnableTask** | Task that does leaf work on a **worker** (`run(RunContext)`) | "leaf task" | `.../tasks/RunnableTask.java` | High |
| **FlowableTask** | Task that orchestrates children on the **executor** (If/Switch/Parallel/Sequential/Loop) | "control-flow task" | `.../tasks/FlowableTask.java` | High |
| **ExecutableTask** | Task that spawns/awaits a child flow (`Subflow`) | | `.../tasks/ExecutableTask.java` | High |
| **Pausable** | Marker: reaching this task pauses the whole execution until resumed | | `.../tasks/Pausable.java`; `Pause.java` | High |
| **State / StateType** | The lifecycle value (18 states) + its append-only history | "status" | `model .../execution/StateType.java`; `.../flows/State.java` | High |
| **Trigger / AbstractTrigger** | What starts an execution without a human | | `.../triggers/AbstractTrigger.java` | High |
| **Schedulable** | Trigger capability: "give me the next fire time" (cron) | | `.../triggers/Schedulable.java`; `Schedule.java` | High |
| **FlowListener** | Trigger capability: fire when another flow reaches a terminal state | "flow trigger" | `.../triggers/FlowListener.java`; `FlowTrigger.java` | High |
| **Input** | Typed, validated, defaulted flow parameter (`STRING/INT/FLOAT/BOOLEAN/DURATION/JSON`) | | `.../flows/Input.java`; `InputResolver.java` | High |
| **FlowOutput** | Named Pebble expression rendered at execution termination | "flow output" | `.../flows/FlowOutput.java`; `Executor.renderFlowOutputs` | High |
| **Output / VoidOutput** | A task's typed produced value (referenced `{{ outputs.<taskId>.<field> }}`) | | `.../tasks/Output.java` | High |
| **Concurrency** | Per-flow parallel-execution `limit` + over-limit `behavior` (QUEUE/CANCEL/FAIL) | | `.../flows/Concurrency.java`; `Executor.admit` | High |
| **RetryPolicy** | Per-task retry: maxAttempts, CONSTANT/EXPONENTIAL backoff, maxDelay, maxDuration budget | | `.../tasks/RetryPolicy.java`; `Executor.tryRetry` | High |
| **RunContext** | The environment a task sees: rendered variables, logger, KV, storage | | `plugin-sdk .../runners/RunContext.java`; `DefaultRunContext` | High |
| **RunContextFactory** | Assembles the variable map (`inputs`/`flow`/`execution`/`outputs`/`taskrun`) | | `RunContextFactory.java` | High |
| **VariableRenderer** | Pebble template engine wrapper (`{{ … }}` expressions/filters/functions) | "templating" | `VariableRenderer.java`; `TrantoPebbleExtension` | High |
| **Queue / topic** | The message backbone; 4 topics: `execution`, `worker-task`, `worker-result`, `execution-updated` | "the wires" | `QueueInterface.java`; `DistributedEngine.java:71-74` | High |
| **Repository** | Store facade for flows and executions (Memory or JDBC) | | `core .../repositories/*` | High |
| **KVStore** | Namespace-scoped durable key/value with TTL (cross-execution state) | "kv" | `plugin-sdk .../kv/KVStore.java`; `MemoryKVStore` | High |
| **StorageInterface** | Object storage for task payloads (`tranto:///` URIs) | "internal storage" | `plugin-sdk .../storages/StorageInterface.java`; `LocalStorage` | High |
| **TaskRunner** | Pluggable backend deciding *where* script commands run (Process/Docker/K8s) | | `.../tasks/runners/TaskRunner.java`; `Process.java` | High |
| **Plugin / @Plugin** | The extension unit; resolved by `type:` (FQCN, `@Plugin.Id`, or alias) | | `.../models/Plugin.java`; `PluginRegistry` | High |
| **Stability wall** | The rule that plugins can't depend on engine internals (SDK-only) | | `docs/08…`; `ArchitectureTest` | High |
| **ServerType** | The role a process plays: LOCAL/STANDALONE/EXECUTOR/SCHEDULER/WORKER/CONTROLLER/INDEXER/WEBSERVER | | `model .../ServerType.java` | High |
| **Executor** | The flow state machine (drives executions to terminal) | | `core .../runners/Executor.java` | High |
| **Worker** | Runs individual RunnableTasks | | `core .../runners/Worker.java` | High |
| **Scheduler** | Fires time-based (cron) triggers | | `core .../schedulers/Scheduler.java` | High |
| **Correlation id / system.** labels | Tranto-managed system labels (prefix `system.`) | | `.../models/Label.java:12` | Medium (prefix exists; usage sparse) |

**Domain verbs:** register, submit, run, dispatch/emit, receive, plan, resolve, start/open (a task-run),
withState/withTaskRun (transition), success/failed/warning/skipped (terminate a task-run), retry,
kill, pause/resume, admit/queue/cancel (concurrency), fire/trigger, tick, render (Pebble), coerce/
validate (inputs), claim (queue message), spawn/join (subflow), rollup (warning), matchUpstream.

---

## Core Entity Lifecycles

### StateType — the master state machine (18 states)

`model/src/main/java/io/tranto/core/models/execution/StateType.java`. Note: **there is no
declarative transition table**; legality is expressed as *predicate partitions* and enforced
procedurally by the Executor, which chooses each `next` and appends it via the immutable
`State.withState`. The predicate sets are the authoritative rules.

```
                         +-----------------------------------------------+
                         v                                               |
[start] --> CREATED --> RUNNING --> SUCCESS   (terminal, success-like)   | (retry re-dispatch:
   ^          |           |     \-> WARNING   (terminal, success-like)   |  task-run SUBMITTED
   |          |           |      \-> FAILED   (terminal, IS failure)     |  again, attempts++)
RESTARTED     |           |       \-> KILLED  (terminal, success-like)   |
              |           |        \-> SKIPPED (terminal, success-like) -+
              |           |
              |           +--> PAUSED --(engine.resume)--> RUNNING
              |           +--> KILLING --(engine.kill)---> KILLED
              |           +--> QUEUED  --(slot frees)-----> RUNNING     (concurrency limit)
              |                        \-(CANCEL behavior)-> CANCELLED  (terminal)
              |                        \-(FAIL behavior)---> FAILED
              +--> (task-run) SUBMITTED --> RUNNING --> ...

Predicate partitions (StateType.java:71-104) — the real rulebook:
  isCreated()          = {CREATED, RESTARTED}                 -> eligible to start
  isRunning()          = {RUNNING, KILLING}
  isPaused()           = {PAUSED}
  isFailed()           = {FAILED}
  isTerminated()       = {SUCCESS, WARNING, FAILED, KILLED, CANCELLED, RETRIED, SKIPPED, RESUBMITTED}
  isTerminatedNoFail() = {SUCCESS, WARNING, KILLED, CANCELLED, SKIPPED}   (success-like subset)

Other states present but with little/no engine wiring (see Open Questions):
  BREAKPOINT, RESUBMITTED, RETRYING (RETRIED is used; RETRYING is not emitted), UNKNOWN (deserialization fallback)
```
- **Born at:** `CREATED` (`State.created()`, `State.java:29`). **Terminal set:** the 8 states above. **Fallback:** unknown strings deserialize to `UNKNOWN` so a newer producer never breaks an older consumer (`StateType.fromString`, `:58-68`).

### Execution — one run of a flow

- **Purpose:** the immutable record of a single flow run; every transition returns a *new* Execution.
- **States (execution-level, set by the Executor):**
```
CREATED --(dispatched)--> RUNNING --> SUCCESS
   |                         |     \-> WARNING   (SUCCESS + any task-run WARNING rolls up: Executor.java:330-334)
   |                         |      \-> FAILED   (any task-run FAILED, after errors/finally: ExecutionPlanner.plan)
   |                         |       \-> KILLED  (engine.kill)
   |                         +-------> PAUSED    (a Pausable task reached; resume -> RUNNING)
   +--> QUEUED (concurrency)  +------> CANCELLED / FAILED (concurrency CANCEL/FAIL)
   +--> FAILED (fail-fast: invalid inputs, before any task runs: StandaloneEngine.failFast:130)

transitions (evidence):
  CREATED --> RUNNING  : Executor.process dispatches first task-runs (Executor.java:303-308)
  RUNNING --> SUCCESS  : ExecutionPlanner.plan returns terminal SUCCESS (all task-runs terminated OK)
  RUNNING --> WARNING  : end() rolls a tolerated WARNING up to the execution (Executor.java:330)
  RUNNING --> FAILED   : a task-run FAILED and no retry/allowFailure covers it
  RUNNING --> PAUSED   : planner hits a Pausable task (ExecutionPlanner.startTask:172)
  RUNNING --> KILLED   : Executor.kill marks all non-terminal runs KILLED (Executor.java:191)
  CREATED --> QUEUED   : Executor.admit, over concurrency limit, QUEUE behavior (Executor.java:120)
  CREATED --> FAILED   : InputResolver validation fails (StandaloneEngine.run:89)
```
- **Born at:** `Execution.newExecution(...)` → `State.created()` (`Execution.java:58-73`). **Terminal states:** SUCCESS/WARNING/FAILED/KILLED/CANCELLED. **Outputs** (rendered FlowOutputs) are attached only at terminal (`Executor.renderFlowOutputs:351`).

### TaskRun — one run of one task

- **Purpose:** immutable per-task record; the executor applies worker results idempotently by replacing the TaskRun in the list by id.
- **States:**
```
CREATED --> SUBMITTED --(worker picks up)--> RUNNING --> SUCCESS  (run() returned)
   |            |                                     \-> WARNING (threw, task.allowFailure=true: Worker.java:65-67)
   |            |                                      \-> FAILED  (threw; or timed out; or runIf eval error)
   |            +--(disabled OR runIf falsy)--------------> SKIPPED (Worker.java:47-48)
   |
FlowableTask task-run: CREATED --> RUNNING (stays RUNNING while children run) --> SUCCESS/FAILED
Pausable task-run:     CREATED --> PAUSED --(resume)--> SUCCESS (Executor.resume:214)
retry:                 FAILED --> SUBMITTED (attempts+1), optionally after backoff delay (Executor.tryRetry)
```
- **Born at:** `TaskRun.of(...)` → `State.created()`. **Terminal:** SUCCESS/WARNING/FAILED/SKIPPED/KILLED. **Nesting:** `parentTaskRunId` builds the tree; iteration scopes use a synthetic parent id `"<flowableRunId>#<value>"` (`ExecutionPlanner.java:232`).

### Flow — the definition (config lifecycle, not runtime state)

- **Purpose:** the reusable definition. Not stateful at runtime; its "lifecycle" is register → (arm triggers) → execute-many → (implicitly) superseded by a new save.
```
[YAML] --parse--> Flow object --register--> ( FlowRepository )  --arms--> Scheduler (cron) + FlowTrigger eligibility
                                    |
                                    +--> executable by namespace/id (submit/run/trigger)

  register: FlowRepository.save + Scheduler.register (StandaloneEngine.register:140)
  NOTE: MemoryFlowRepository keeps only the LATEST save per (tenant|namespace|id) — no revision history.
```
- **Born at:** `YamlFlowParser.parse` / `POST /api/v1/flows`. **Terminal state:** none — overwritten on re-save (revision history is not retained: Landmine #5).

### Trigger — how executions start without a human

```
Schedule (Schedulable): Scheduler holds next-fire per (flow,trigger); tick(now) fires when due; onFire=submit
FlowTrigger (FlowListener): FlowTriggerEvaluator.onTerminal scans all flows on every terminal execution;
                            matchesUpstream(namespace, flowId, state) -> startFlow(submit). Never self-triggers.
```
- **Born at:** flow registration. **Fires:** indefinitely (cron) / on each matching upstream terminal (flow trigger). Cron trigger `nextEvaluationDate` returns null → never fires again (`CronExpression.next` bounded to 4 years).
